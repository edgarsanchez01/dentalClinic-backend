package com.dentalclinic.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.dentalclinic.identity.infrastructure.config.IdentityAbuseProperties;
import com.dentalclinic.identity.infrastructure.token.TokenHasher;

/**
 * Límite local de intentos por IP (y respaldo de cuenta para el modo legacy sin outbox).
 * Ventana fija que los rechazos no prolongan; memoria acotada, sin PII ni logs de identificadores.
 * Los límites de emisiones HMAC por cuenta son durables y viven en IdentityIssuancePolicy.
 */
@Component
public class IdentityRequestLimiter {
    private final Map<String, Bucket> buckets = new HashMap<>();
    private final IdentityAbuseProperties properties;
    private final TokenHasher hasher;
    private final Clock clock;

    public IdentityRequestLimiter(IdentityAbuseProperties properties, TokenHasher hasher, Clock clock) {
        this.properties = properties;
        this.hasher = hasher;
        this.clock = clock;
    }

    public boolean allowResetIp(String ip) {
        return allow("reset-ip:" + ip, properties.ipWindow(), properties.resetMaxPerIp(), Duration.ZERO);
    }

    public boolean allowStaffIp(String ip) {
        return allow("staff-ip:" + ip, properties.ipWindow(), properties.staffMaxPerIp(), Duration.ZERO);
    }

    public boolean allowLegacyStaff(Long userId) {
        return allow("legacy-staff:" + userId, properties.staffWindow(), properties.staffMaxPerAccount(),
                properties.staffCooldown());
    }

    public boolean allowLegacyReset(Long userId) {
        return allow("legacy-reset:" + userId, properties.resetWindow(), properties.resetMaxPerAccount(),
                properties.resetCooldown());
    }

    private synchronized boolean allow(String identity, Duration window, int maximum, Duration cooldown) {
        Instant now = clock.instant();
        buckets.values().removeIf(bucket -> !bucket.until.isAfter(now));
        String key = hasher.hash(identity);
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= properties.maxIpEntries()) {
                return false;
            }
            bucket = new Bucket(now.plus(window));
            buckets.put(key, bucket);
        }
        if (bucket.count >= maximum || (bucket.last != null && bucket.last.plus(cooldown).isAfter(now))) {
            return false;
        }
        bucket.count++;
        bucket.last = now;
        return true;
    }

    private static final class Bucket {
        private final Instant until;
        private int count;
        private Instant last;

        private Bucket(Instant until) {
            this.until = until;
        }
    }
}

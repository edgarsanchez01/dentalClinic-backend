package com.dentalclinic.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.dentalclinic.identity.infrastructure.config.IdentityAbuseProperties;
import com.dentalclinic.identity.infrastructure.token.TokenHasher;

class IdentityRequestLimiterTest {
    @Test
    void concurrenciaNoSuperaElLimite() throws Exception {
        var properties = new IdentityAbuseProperties(null, null, 0, null, null, 0,
                null, 3, 3, 100, List.of());
        var limiter = new IdentityRequestLimiter(properties, new TokenHasher(), Clock.systemUTC());
        try (var pool = Executors.newFixedThreadPool(8)) {
            var calls = java.util.stream.IntStream.range(0, 30)
                    .mapToObj(i -> (java.util.concurrent.Callable<Boolean>) () -> limiter.allowResetIp("192.0.2.1"))
                    .toList();
            int allowed = 0;
            for (var future : pool.invokeAll(calls)) {
                if (future.get(5, TimeUnit.SECONDS)) { allowed++; }
            }
            assertThat(allowed).isEqualTo(3);
        }
    }

    @Test
    void rechazosNoProlonganVentanaYLaMemoriaEsAcotada() {
        var now = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-10-16T00:00:00Z"));
        Clock clock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return now.get(); }
        };
        var properties = new IdentityAbuseProperties(null, null, 0, null, null, 0,
                Duration.ofMinutes(1), 1, 1, 1, List.of());
        var limiter = new IdentityRequestLimiter(properties, new TokenHasher(), clock);
        assertThat(limiter.allowResetIp("192.0.2.1")).isTrue();
        assertThat(limiter.allowResetIp("192.0.2.2")).isFalse();
        now.updateAndGet(value -> value.plusSeconds(59));
        assertThat(limiter.allowResetIp("192.0.2.1")).isFalse();
        now.updateAndGet(value -> value.plusSeconds(1));
        assertThat(limiter.allowResetIp("192.0.2.2")).isTrue();
    }
}

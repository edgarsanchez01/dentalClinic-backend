package com.dentalclinic.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

import com.dentalclinic.identity.domain.IdentityEmailPurpose;
import com.dentalclinic.identity.domain.IdentityEmailStatus;
import com.dentalclinic.identity.infrastructure.config.IdentityAbuseProperties;
import com.dentalclinic.identity.infrastructure.config.IdentityLinkProperties;
import com.dentalclinic.identity.infrastructure.repository.IdentityEmailOutboxRepository;
import com.dentalclinic.identity.infrastructure.repository.PasswordResetTokenRepository;
import com.dentalclinic.identity.infrastructure.token.IdentityLinkTokens;

/** Límites durables de emisiones por cuenta. Invocar bajo el lock de users, en la tx de emisión. */
@Component
public class IdentityIssuancePolicy {
    private final IdentityEmailOutboxRepository outbox;
    private final IdentityAbuseProperties properties;
    private final IdentityLinkProperties links;
    private final IdentityRequestLimiter local;
    private final Clock clock;
    private final PasswordResetTokenRepository resets;
    private final IdentityLinkTokens tokens;

    public IdentityIssuancePolicy(IdentityEmailOutboxRepository outbox, IdentityAbuseProperties properties,
            IdentityLinkProperties links, IdentityRequestLimiter local, Clock clock,
            PasswordResetTokenRepository resets, IdentityLinkTokens tokens) {
        this.outbox = outbox;
        this.properties = properties;
        this.links = links;
        this.local = local;
        this.clock = clock;
        this.resets = resets;
        this.tokens = tokens;
    }

    /** No sustituir un enlace entregable/vivo. Legacy sin transporte o FAILED sí puede reemitirse. */
    public boolean hasOutstandingReset(Long userId, Instant now) {
        if (!links.configured()) {
            return resets.existsByUserIdAndUsedAtIsNullAndExpiresAtAfter(userId, now);
        }
        return resets.findByUserIdAndUsedAtIsNullAndExpiresAtAfter(userId, now).stream()
                .anyMatch(reset -> outbox.findByTokenHash(reset.getTokenHash())
                        .filter(row -> row.getUserId().equals(userId))
                        .filter(row -> row.getPurpose() == IdentityEmailPurpose.PASSWORD_RESET)
                        .filter(row -> row.getSupersededAt() == null && row.getStatus() != IdentityEmailStatus.FAILED)
                        .filter(row -> tokens.hash(tokens.token(IdentityLinkTokens.Domain.PASSWORD_RESET,
                                userId, row.getTokenNonce())).equals(reset.getTokenHash()))
                        .isPresent());
    }

    public boolean allowReset(Long userId) {
        return links.configured()
                ? allowed(userId, IdentityEmailPurpose.PASSWORD_RESET, properties.resetCooldown(),
                        properties.resetWindow(), properties.resetMaxPerAccount())
                : local.allowLegacyReset(userId);
    }

    public boolean allowStaff(Long userId) {
        return links.configured()
                ? allowed(userId, IdentityEmailPurpose.STAFF_INVITATION, properties.staffCooldown(),
                        properties.staffWindow(), properties.staffMaxPerAccount())
                : local.allowLegacyStaff(userId);
    }

    private boolean allowed(Long userId, IdentityEmailPurpose purpose, Duration cooldown,
            Duration window, int maximum) {
        var now = clock.instant();
        return !outbox.existsByUserIdAndPurposeAndCreatedAtAfter(userId, purpose, now.minus(cooldown))
                && outbox.countByUserIdAndPurposeAndCreatedAtAfter(userId, purpose, now.minus(window)) < maximum;
    }
}

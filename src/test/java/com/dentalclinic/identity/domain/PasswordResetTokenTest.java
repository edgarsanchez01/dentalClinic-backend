package com.dentalclinic.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** Token de recuperación: hasheado, expirable y de un solo uso (RF-IA-12). */
class PasswordResetTokenTest {

    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    @Test
    void recienEmitido_EsUsable() {
        PasswordResetToken token = PasswordResetToken.issue(5L, "hash", NOW.plusSeconds(3600));
        assertThat(token.getUserId()).isEqualTo(5L);
        assertThat(token.isUsableAt(NOW)).isTrue();
        assertThat(token.getUsedAt()).isEmpty();
    }

    @Test
    void expirado_NoEsUsable() {
        PasswordResetToken token = PasswordResetToken.issue(5L, "hash", NOW.minusSeconds(5));
        assertThat(token.isUsableAt(NOW)).isFalse();
    }

    @Test
    void consumido_NoEsUsable() {
        PasswordResetToken token = PasswordResetToken.issue(5L, "hash", NOW.plusSeconds(3600));
        token.markUsedAt(NOW);
        assertThat(token.isUsableAt(NOW.plusSeconds(1))).isFalse();
    }

    @Test
    void exactamenteEnLaCaducidad_NoEsUsable() {
        PasswordResetToken token = PasswordResetToken.issue(5L, "hash", NOW);
        assertThat(token.isUsableAt(NOW)).isFalse();
    }
}
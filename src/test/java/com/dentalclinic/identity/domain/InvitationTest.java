package com.dentalclinic.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Invitación: token hasheado, expirable, de un solo uso y con los dos modos que
 * describen los documentos (cuenta ya creada / cuenta creada al activar).
 */
class InvitationTest {

    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    @Test
    void issue_ModoPaciente_sinCuentaPrevia() {
        Invitation invitation = Invitation.issue("hash", "ana@clinica.com", Role.RECEPTIONIST,
                NOW.plusSeconds(3600));
        assertThat(invitation.getEmail()).isEqualTo("ana@clinica.com");
        assertThat(invitation.getRole()).isEqualTo(Role.RECEPTIONIST);
        assertThat(invitation.getUserId()).isEmpty();
        assertThat(invitation.isUsableAt(NOW)).isTrue();
        assertThat(invitation.getUsedAt()).isEmpty();
    }

    @Test
    void issueForExistingUser_ModoPersonal_conCuentaPrevista() {
        Invitation invitation = Invitation.issueForExistingUser("hash", "dent@clinica.com",
                Role.DENTIST, 42L, NOW.plusSeconds(3600));
        assertThat(invitation.getUserId()).contains(42L);
    }

    @Test
    void issue_NuncaOtorgaOWNER() {
        assertThatThrownBy(() -> Invitation.issue("hash", "x@clinica.com", Role.OWNER, NOW.plusSeconds(3600)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("P-07");
        assertThatThrownBy(() -> Invitation.issueForExistingUser("hash", "x@clinica.com", Role.OWNER,
                1L, NOW.plusSeconds(3600)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("P-07");
    }

    @Test
    void caducada_NoEsUsable() {
        Invitation invitation = Invitation.issue("hash", "ana@clinica.com", Role.RECEPTIONIST,
                NOW.minusSeconds(60));
        assertThat(invitation.isUsableAt(NOW)).isFalse();
    }

    @Test
    void incentroDelPlazo_EsUsable() {
        Invitation invitation = Invitation.issue("hash", "ana@clinica.com", Role.RECEPTIONIST,
                NOW.plusSeconds(60));
        assertThat(invitation.isUsableAt(NOW)).isTrue();
    }

    @Test
    void consumida_YaNoEsUsable() {
        Invitation invitation = Invitation.issue("hash", "ana@clinica.com", Role.RECEPTIONIST,
                NOW.plusSeconds(3600));
        invitation.markUsedAt(NOW, 7L);
        assertThat(invitation.getUsedAt()).contains(NOW);
        assertThat(invitation.isUsableAt(NOW.plusSeconds(1))).isFalse();
    }

    @Test
    void markUsedAt_NoPuedeReasignarCuenta() {
        Invitation invitation = Invitation.issueForExistingUser("hash", "dent@clinica.com",
                Role.DENTIST, 42L, NOW.plusSeconds(3600));
        assertThatThrownBy(() -> invitation.markUsedAt(NOW, 43L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reasign");
        assertThatNoException().isThrownBy(() -> invitation.markUsedAt(NOW, 42L));
    }
}
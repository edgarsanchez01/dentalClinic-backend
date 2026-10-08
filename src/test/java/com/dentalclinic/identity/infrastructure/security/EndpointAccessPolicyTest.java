package com.dentalclinic.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.dentalclinic.identity.domain.Role;

/**
 * Tests exhaustivos de la matriz de autorización §7.4.
 *
 * <p>La matriz es normativa ("El backend la implementa", PRD §5.3) y vive en
 * {@link EndpointGroup}. Aquí se comprueba el comportamiento derivado por combinación de
 * grupo × rol × método, y el fallo cerrado para lo que no pertenece a la matriz.
 */
class EndpointAccessPolicyTest {

    private final EndpointAccessPolicy policy = new EndpointAccessPolicy();

    @Test
    void combinacionesDeMatriz_Exhaustivas() {
        for (EndpointGroup group : EndpointGroup.values()) {
            String representativePath = representativePath(group);
            for (Role role : Role.values()) {
                AccessLevel level = group.levelFor(role);
                boolean readExpected = level != AccessLevel.NONE;
                boolean writeExpected = level == AccessLevel.READ_WRITE || level == AccessLevel.SELF_READ_WRITE;
                boolean ownershipExpected = level == AccessLevel.SELF_READ || level == AccessLevel.SELF_READ_WRITE;

                Optional<EndpointAccessPolicy.EndpointDecision> read =
                        policy.decide(representativePath, "GET", role);
                Optional<EndpointAccessPolicy.EndpointDecision> write =
                        policy.decide(representativePath, "POST", role);

                assertThat(read).as("%s GET %s escalado a %s", group, role, level).isPresent();
                assertThat(write).as("%s POST %s escalado a %s", group, role, level).isPresent();
                assertThat(read.get().permitted()).as("%s GET %s", group, role).isEqualTo(readExpected);
                assertThat(write.get().permitted()).as("%s POST %s", group, role).isEqualTo(writeExpected);
                assertThat(read.get().ownershipCheckRequired())
                        .as("ownership %s %s", group, role).isEqualTo(ownershipExpected);
                assertThat(read.get().level()).isEqualTo(level);
                assertThat(write.get().level()).isEqualTo(level);
            }
        }
    }

    @Test
    void clinicalSeResuelveAntesQuePatients() {
        // PATIENTS daría a PATIENT SELF_READ sobre /api/patients/5/clinical/... El orden de
        // EndpointGroup debe mantenerlo fuera: historial clínico es solo personal clínico.
        Optional<AccessLevel> level = policy.levelFor("/api/patients/5/clinical/history", Role.PATIENT);
        assertThat(level).contains(AccessLevel.NONE);
        assertThat(policy.decide("/api/patients/5/clinical/history", "GET", Role.PATIENT))
                .map(EndpointAccessPolicy.EndpointDecision::permitted).contains(false);
        // DENTIST sí lo gestiona.
        assertThat(policy.decide("/api/patients/5/clinical/history", "POST", Role.DENTIST))
                .map(EndpointAccessPolicy.EndpointDecision::permitted).contains(true);
    }

    @Test
    void odontogramaDaSoloLecturaPropiaAlPaciente() {
        Optional<EndpointAccessPolicy.EndpointDecision> read =
                policy.decide("/api/patients/5/odontogram/summary", "GET", Role.PATIENT);
        assertThat(read).map(EndpointAccessPolicy.EndpointDecision::permitted).contains(true);
        assertThat(read).map(EndpointAccessPolicy.EndpointDecision::ownershipCheckRequired).contains(true);
        assertThat(policy.decide("/api/patients/5/odontogram/summary", "POST", Role.PATIENT))
                .map(EndpointAccessPolicy.EndpointDecision::permitted).contains(false);
    }

    @Test
    void createOwner_NadiePuedeNiEquity() {
        assertThat(policy.decide("/api/users/owners", "GET", Role.OWNER))
                .map(EndpointAccessPolicy.EndpointDecision::permitted).contains(false);
        assertThat(policy.decide("/api/users/owners", "POST", Role.OWNER))
                .map(EndpointAccessPolicy.EndpointDecision::permitted).contains(false);
        for (Role role : Role.values()) {
            assertThat(policy.decide("/api/users/owners", "POST", role))
                    .map(EndpointAccessPolicy.EndpointDecision::permitted).contains(false);
        }
    }

    @Test
    void usuariosSoloOwner() {
        for (Role role : EnumSet.complementOf(EnumSet.of(Role.OWNER))) {
            assertThat(policy.decide("/api/users/7", "GET", role))
                    .map(EndpointAccessPolicy.EndpointDecision::permitted).contains(false);
        }
        assertThat(policy.decide("/api/users/7", "GET", Role.OWNER))
                .map(EndpointAccessPolicy.EndpointDecision::permitted).contains(true);
    }

    @Test
    void fueraDeLaMatriz_FalloCerrado() {
        assertThat(policy.levelFor("/api/no-existe", Role.OWNER)).isEmpty();
        assertThat(policy.levelFor("/api/nada/que/ver", Role.DENTIST)).isEmpty();
        // No es /api: fuera del alcance del filtro (lo gobierna la cadena).
        assertThat(policy.levelFor("/actuator/health", Role.OWNER)).isEmpty();
    }

    @Test
    void selfReadWrite_NuncaPermiteEscribirAjeno_PeroMarcaOwnership() {
        Optional<EndpointAccessPolicy.EndpointDecision> decision =
                policy.decide("/api/appointments/9", "POST", Role.PATIENT);
        assertThat(decision).map(EndpointAccessPolicy.EndpointDecision::permitted).contains(true);
        assertThat(decision).map(EndpointAccessPolicy.EndpointDecision::ownershipCheckRequired).contains(true);
    }

    private String representativePath(EndpointGroup group) {
        String pattern = group.pattern();
        String path = pattern;
        if (path.endsWith("/**")) {
            // /api/users/** -> /api/users/1 (el comodin /** exige al menos un segmento).
            path = path.substring(0, path.length() - 3) + "/1";
        }
        // Segmentos comodin intermedios: /api/patients/*/clinical/1 -> /api/patients/1/clinical/1.
        return path.replace("/*/", "/1/");
    }
}
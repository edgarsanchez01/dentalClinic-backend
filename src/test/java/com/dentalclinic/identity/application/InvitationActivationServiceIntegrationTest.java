package com.dentalclinic.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.dentalclinic.identity.IdentityIntegrationTestSupport;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.domain.Invitation;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.domain.exception.EmailAlreadyRegisteredException;
import com.dentalclinic.identity.domain.exception.InvalidTokenException;
import com.dentalclinic.identity.domain.exception.PasswordExceedsHashingLimitException;
import com.dentalclinic.identity.infrastructure.repository.InvitationRepository;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;
import com.dentalclinic.identity.infrastructure.security.SecurityProperties;
import com.dentalclinic.identity.infrastructure.token.TokenHasher;

/**
 * Consumo de invitaciones (RF-IA-09, PRD §6.5.2, P-03): token hasheado, expirable, un solo uso.
 * Here se prueba el lado consumidor: el emisor es Fase 3/Fase 4.
 */
class InvitationActivationServiceIntegrationTest extends IdentityIntegrationTestSupport {

    private static final String PASSWORD = "OtraClave-2026";

    @Autowired
    private InvitationActivationService activationService;
    @Autowired
    private InvitationRepository invitationRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private TokenHasher tokenHasher;
    @Autowired
    private SecurityProperties properties;

    @BeforeEach
    void cleanSlate() {
        TestTime.reset();
        invitationRepository.deleteAll();
        userRepository.deleteAll();
    }

    private Invitation store(String email, Role role, Duration ttl, Long userId) {
        String token = "token-opaco-" + email;
        Invitation invitation = userId == null
                ? Invitation.issue(tokenHasher.hash(token), email, role, TestTime.now().plus(ttl))
                : Invitation.issueForExistingUser(tokenHasher.hash(token), email, role, userId,
                        TestTime.now().plus(ttl));
        return invitationRepository.saveAndFlush(invitation);
    }

    @Test
    void activa_ModoPaciente_CreaCuentaActivaSinVerificacion() {
        Invitation invitation = store("Paciente@Clinica.com", Role.PATIENT, Duration.ofHours(72), null);

        User activated = activationService.activate("token-opaco-Paciente@Clinica.com", PASSWORD);

        assertThat(activated).isNotNull();
        assertThat(activated.getEmail()).isEqualTo("paciente@clinica.com");
        assertThat(activated.getRole()).isEqualTo(Role.PATIENT);
        assertThat(activated.isActive()).as("P-03: sin verificacion de email, cuenta nace activa").isTrue();
        assertThat(passwordEncoder.matches(PASSWORD, activated.getPasswordHash())).isTrue();

        // La invitacion queda ligada a la cuenta y consumida.
        Invitation updated = invitationRepository.findById(invitation.getId()).orElseThrow();
        assertThat(updated.getUserId()).contains(activated.getId());
        assertThat(updated.getUsedAt()).isPresent();
        assertThat(updated.isUsableAt(TestTime.now())).isFalse();
    }

    @Test
    void activa_ModoPersonal_ActivaLaCuentaYaCreada() {
        User pending = userRepository.saveAndFlush(
                User.createPendingActivation("dent@clinica.com",
                        passwordEncoder.encode("contraseña-temporal"), Role.DENTIST));
        Invitation invitation = store("dent@clinica.com", Role.DENTIST, Duration.ofHours(72), pending.getId());

        User activated = activationService.activate("token-opaco-" + invitation.getEmail(), PASSWORD);

        assertThat(userRepository.findById(pending.getId()).orElseThrow().isActive()).isTrue();
        assertThat(activated.getId()).isEqualTo(pending.getId());
        assertThat(passwordEncoder.matches(PASSWORD, activated.getPasswordHash())).isTrue();
    }

    @Test
    void activa_Doblemente_NoCreaDosCuentas() {
        store("ana@clinica.com", Role.PATIENT, Duration.ofHours(72), null);

        activationService.activate("token-opaco-ana@clinica.com", PASSWORD);
        assertThatThrownBy(() -> activationService.activate("token-opaco-ana@clinica.com", PASSWORD))
                .isInstanceOf(InvalidTokenException.class);
        assertThat(userRepository.findByEmail("ana@clinica.com").orElseThrow().isActive()).isTrue();
    }

    @Test
    void activa_TokenCaducado_Rechaza() {
        Invitation invitation = store("old@clinica.com", Role.PATIENT, Duration.ofHours(-1), null);

        assertThatThrownBy(() -> activationService.activate("token-opaco-old@clinica.com", PASSWORD))
                .isInstanceOf(InvalidTokenException.class);
        assertThat(userRepository.findByEmail("old@clinica.com")).isEmpty();
        assertThat(invitationRepository.findById(invitation.getId()).orElseThrow().getUsedAt()).isEmpty();
    }

    @Test
    void activa_TokenDesconocido_Neutral() {
        assertThatThrownBy(() -> activationService.activate("token-inexistente", PASSWORD))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void activa_EmailYaExistente_409ViaExcepcionDeDominio() {
        userRepository.saveAndFlush(User.create("ana@clinica.com",
                passwordEncoder.encode("otra"), Role.PATIENT));
        store("ANA@Clinica.com", Role.PATIENT, Duration.ofHours(72), null);

        assertThatThrownBy(() -> activationService.activate("token-opaco-ANA@Clinica.com", PASSWORD))
                .isInstanceOf(EmailAlreadyRegisteredException.class);
    }

    @Test
    void activa_ContrasenaMasDe72Bytes_Rechaza() {
        store("ana@clinica.com", Role.PATIENT, Duration.ofHours(72), null);
        assertThatThrownBy(() -> activationService.activate("token-opaco-ana@clinica.com", "a".repeat(73)))
                .isInstanceOf(PasswordExceedsHashingLimitException.class);
    }

    @Test
    void dosActivacionesSimultaneas_SoloUnaCuenta() throws Exception {
        store("concurrente@clinica.com", Role.PATIENT, Duration.ofHours(24), null);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Boolean> first = pool.submit(activateConcurrently(go, "concurrente@clinica.com"));
            Future<Boolean> second = pool.submit(activateConcurrently(go, "concurrente@clinica.com"));
            go.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);

            assertThat(userRepository.findByEmail("concurrente@clinica.com"))
                    .as("una invitacion de un solo uso no puede dar de alta dos cuentas").isPresent();
            assertThat(userRepository.findAll().size()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Boolean> activateConcurrently(CountDownLatch go, String email) {
        return () -> {
            go.await(5, TimeUnit.SECONDS);
            try {
                activationService.activate("token-opaco-" + email, PASSWORD);
            } catch (Exception expectedWhenLosingRace) {
                // El perdedor puede acabar en InvalidTokenException o en violacion de unicidad;
                // la invariante solo exige que no se consume dos veces y que no haya dos cuentas.
            }
            return true;
        };
    }

    @Test
    void caducidadSeLeeDeLaPropiedadTecnicaDePruebas() {
        assertThat(properties.invitation().ttl()).isEqualTo(Duration.ofDays(1));
    }
}
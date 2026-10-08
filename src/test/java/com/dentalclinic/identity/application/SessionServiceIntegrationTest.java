package com.dentalclinic.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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
import com.dentalclinic.identity.domain.RefreshSession;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.domain.exception.AccountInactiveException;
import com.dentalclinic.identity.domain.exception.InvalidCredentialsException;
import com.dentalclinic.identity.domain.exception.NeutralAuthenticationException;
import com.dentalclinic.identity.domain.exception.PasswordExceedsHashingLimitException;
import com.dentalclinic.identity.infrastructure.repository.RefreshSessionRepository;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;
import com.dentalclinic.identity.infrastructure.token.TokenHasher;

/**
 * Comportamiento observable de P-02 (CLOSED) y §7.1 sobre PostgreSQL real.
 */
class SessionServiceIntegrationTest extends IdentityIntegrationTestSupport {

    private static final String PASSWORD = "Cl4veSegura-2026";

    @Autowired
    private SessionService sessionService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RefreshSessionRepository refreshSessionRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private TokenHasher tokenHasher;

    @BeforeEach
    void cleanSlate() {
        TestTime.reset();
        refreshSessionRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void login_EmiteAccessYFamiliaNueva() {
        User user = createUser("ana@clinica.com", Role.PATIENT, true, PASSWORD);

        IssuedSession session = sessionService.login("  ANA@Clinica.COM ", PASSWORD);

        assertThat(session).isNotNull();
        assertThat(session.accessToken()).isNotBlank();
        assertThat(session.refreshToken()).isNotBlank();
        assertThat(session.user().getId()).isEqualTo(user.getId());
        // Refresh de 30 dias con ventana deslizante (§7.1).
        assertThat(session.refreshTokenExpiresAt()).isEqualTo(TestTime.now().plus(Duration.ofDays(30)));

        RefreshSession persisted = refreshSessionRepository.findAll().get(0);
        // Solo el hash se persiste, nunca el token (§7.1).
        assertThat(persisted.getTokenHash()).isNotEqualTo(session.refreshToken());
        assertThat(persisted.getTokenHash()).isEqualTo(tokenHasher.hash(session.refreshToken()));
        assertThat(persisted.getFamilyId()).isNotNull();
        assertThat(persisted.getUserId()).isEqualTo(user.getId());
    }

    @Test
    void login_NormalizaElEmailParaBuscar() {
        User user = createUser("ana@clinica.com", Role.PATIENT, true, PASSWORD);
        IssuedSession session = sessionService.login("Ana@Clinica.com", PASSWORD);
        assertThat(session.user().getId()).isEqualTo(user.getId());
    }

    @Test
    void login_EmailDesconocido_FallaIgualQueContrasenaMala() {
        assertThatThrownBy(() -> sessionService.login("no@existe.com", PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class);
        createUser("real@clinica.com", Role.PATIENT, true, PASSWORD);
        assertThatThrownBy(() -> sessionService.login("real@clinica.com", "mala"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_CuentaInactiva_YaConLaContrasenaCorrecta() {
        createUser("pend@clinica.com", Role.DENTIST, false, PASSWORD);
        // Solo quien conoce la contrasena recibe la pista de activacion.
        assertThatThrownBy(() -> sessionService.login("pend@clinica.com", PASSWORD))
                .isInstanceOf(AccountInactiveException.class);
    }

    @Test
    void login_ContrasenaDe73Bytes_FallaAntesDeBCrypt() {
        createUser("ana@clinica.com", Role.PATIENT, true, PASSWORD);
        // El limite tecnico se comprueba antes de matches(): es un 400 accionable, no una
        // credencial invalida. PasswordPolicy documenta que BCrypt no aplica el limite al comparar.
        assertThatThrownBy(() -> sessionService.login("ana@clinica.com", "a".repeat(73)))
                .isInstanceOf(PasswordExceedsHashingLimitException.class);
    }

    @Test
    void refresh_RotaYExtiendeLaVentanaDeslizante() {
        IssuedSession first = login("ana@clinica.com");
        TestTime.advance(Duration.ofDays(10));

        IssuedSession rotated = sessionService.refresh(first.refreshToken());

        assertThat(rotated.refreshToken()).isNotEqualTo(first.refreshToken());
        // Ventana deslizante: se reinicia a partir de AHORA (cono desde TestTime=+10d).
        assertThat(rotated.refreshTokenExpiresAt()).isEqualTo(TestTime.now().plus(Duration.ofDays(30)));
        assertThat(rotated.familyId()).isEqualTo(first.familyId());

        Optional<RefreshSession> oldRow = refreshSessionRepository
                .findByTokenHash(tokenHasher.hash(first.refreshToken()));
        assertThat(oldRow).isPresent();
        assertThat(oldRow.get().getRotatedAt()).isNotNull();
    }

    @Test
    void refresh_ReutilizarTokenRotado_RevocaFamiliaYRespondeNeutro() {
        IssuedSession session = login("ana@clinica.com");
        IssuedSession rotated = sessionService.refresh(session.refreshToken());

        assertThatThrownBy(() -> sessionService.refresh(session.refreshToken()))
                .isInstanceOf(NeutralAuthenticationException.class);

        // La familia completa queda revocada: tampoco sirve el token recien rotado.
        assertThatThrownBy(() -> sessionService.refresh(rotated.refreshToken()))
                .isInstanceOf(NeutralAuthenticationException.class);
        for (RefreshSession row : refreshSessionRepository.findAll()) {
            assertThat(row.getRevokedAt()).as("familia %s", row.getFamilyId()).isNotNull();
        }
    }

    @Test
    void refresh_Caducado_NeutralYNoRevocaPorFamiliaAjenaErrorDeCaducidadVistaComoReuso() {
        IssuedSession session = login("ana@clinica.com");
        TestTime.advance(Duration.ofDays(31));
        assertThatThrownBy(() -> sessionService.refresh(session.refreshToken()))
                .isInstanceOf(NeutralAuthenticationException.class);
    }

    @Test
    void refresh_TokenDesconocido_Neutral() {
        assertThatThrownBy(() -> sessionService.refresh("token-que-nunca-existio"))
                .isInstanceOf(NeutralAuthenticationException.class);
    }

    @Test
    void logout_RevocaSoloLaFamiliaActual() {
        IssuedSession first = login("ana@clinica.com");
        IssuedSession second = login("ana@clinica.com");
        assertThat(first.familyId()).isNotEqualTo(second.familyId());

        sessionService.logout(first.refreshToken());

        assertThatThrownBy(() -> sessionService.refresh(first.refreshToken()))
                .isInstanceOf(NeutralAuthenticationException.class);
        // La otra sesion del mismo usuario sigue viva.
        assertThat(sessionService.refresh(second.refreshToken()).accessToken()).isNotBlank();
    }

    @Test
    void logout_EsIdempotente() {
        IssuedSession session = login("ana@clinica.com");
        sessionService.logout(session.refreshToken());
        // Segunda llamada con el mismo token: no lanza.
        sessionService.logout(session.refreshToken());
    }

    @Test
    void logout_SinToken_NoHaceNada() {
        sessionService.logout(null);
        sessionService.logout("");
    }

    @Test
    void revokeAllSessions_RevocaTodasLasFamilias() {
        IssuedSession first = login("ana@clinica.com");
        IssuedSession second = login("ana@clinica.com");

        sessionService.revokeAllSessions(first.user().getId());

        assertThatThrownBy(() -> sessionService.refresh(first.refreshToken()))
                .isInstanceOf(NeutralAuthenticationException.class);
        assertThatThrownBy(() -> sessionService.refresh(second.refreshToken()))
                .isInstanceOf(NeutralAuthenticationException.class);
    }

    @Test
    void desactivarUsuario_CortaElRefresh() {
        IssuedSession session = login("ana@clinica.com");
        User user = userRepository.findById(session.user().getId()).orElseThrow();
        user.deactivate();
        userRepository.saveAndFlush(user);

        assertThatThrownBy(() -> sessionService.refresh(session.refreshToken()))
                .isInstanceOf(NeutralAuthenticationException.class);
    }

    @Test
    void killSwitch_RevocaLasFamiliasActivas() {
        IssuedSession session = login("an.a@clinica.com");
        User user = userRepository.findById(session.user().getId()).orElseThrow();
        user.deactivate();
        userRepository.saveAndFlush(user);

        // El kill-switch corta la renovacion y, al hacerlo, revoca la familia en la base de
        // datos (la respuesta neutra no debe deshacer esa revocacion).
        assertThatThrownBy(() -> sessionService.refresh(session.refreshToken()))
                .isInstanceOf(NeutralAuthenticationException.class);
        assertThat(refreshSessionRepository.findByUserIdAndRevokedAtIsNull(user.getId())).isEmpty();
    }

    @Test
    void dosRenovacionesSimultaneas_SoloUnaVale() throws Exception {
        IssuedSession session = login("ana@clinica.com");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        try {
            Future<IssuedSession> first = pool.submit(() -> runRefresh(session.refreshToken(), ready, go));
            Future<IssuedSession> second = pool.submit(() -> runRefresh(session.refreshToken(), ready, go));
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();

            int successes = 0;
            int neutrals = 0;
            for (Future<IssuedSession> future : new Future[]{first, second}) {
                try {
                    IssuedSession issue = future.get(10, TimeUnit.SECONDS);
                    if (issue != null) {
                        successes++;
                    }
                } catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() instanceof NeutralAuthenticationException) {
                        neutrals++;
                    } else {
                        throw e;
                    }
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(neutrals).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private IssuedSession runRefresh(String token, CountDownLatch ready, CountDownLatch go)
            throws InterruptedException {
        ready.countDown();
        go.await(5, TimeUnit.SECONDS);
        return sessionService.refresh(token);
    }

    private IssuedSession login(String email) {
        // Reutiliza el usuario si ya existe: varias sesiones de la misma cuenta son el
        // escenario de familia multiple que cubren estos tests.
        if (userRepository.findByEmail(email).isEmpty()) {
            createUser(email, Role.PATIENT, true, PASSWORD);
        }
        return sessionService.login(email, PASSWORD);
    }

    private User createUser(String email, Role role, boolean active, String password) {
        User user = active
                ? User.create(email, passwordEncoder.encode(password), role)
                : User.createPendingActivation(email, passwordEncoder.encode(password), role);
        return userRepository.saveAndFlush(user);
    }
}
package com.dentalclinic.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.dentalclinic.identity.IdentityIntegrationTestSupport;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.domain.PasswordResetToken;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.domain.exception.InvalidCredentialsException;
import com.dentalclinic.identity.domain.exception.InvalidTokenException;
import com.dentalclinic.identity.domain.exception.MissingCurrentPasswordException;
import com.dentalclinic.identity.domain.exception.NeutralAuthenticationException;
import com.dentalclinic.identity.domain.exception.PasswordExceedsHashingLimitException;
import com.dentalclinic.identity.infrastructure.repository.PasswordResetTokenRepository;
import com.dentalclinic.identity.infrastructure.repository.RefreshSessionRepository;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;
import com.dentalclinic.identity.infrastructure.security.SecurityProperties;
import com.dentalclinic.identity.infrastructure.token.TokenHasher;

/**
 * RF-IA-11 (cambio) y RF-IA-12 (recuperación): el token se almacena hasheado, es de un solo uso y
 * expirable, y toda renovación/establecimiento de contraseña revoca las sesiones activas.
 */
class PasswordServiceIntegrationTest extends IdentityIntegrationTestSupport {

    private static final String PASSWORD = "ClaveOriginal-2026";
    private static final String NEW_PASSWORD = "ClaveNueva-2027";

    @Autowired
    private PasswordService passwordService;
    @Autowired
    private SessionService sessionService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordResetTokenRepository resetTokenRepository;
    @Autowired
    private RefreshSessionRepository refreshSessionRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private TokenHasher tokenHasher;
    @Autowired
    private SecurityProperties properties;

    @BeforeEach
    void cleanSlate() {
        TestTime.reset();
        resetTokenRepository.deleteAll();
        refreshSessionRepository.deleteAll();
        userRepository.deleteAll();
    }

    private User createActive(String email) {
        return userRepository.saveAndFlush(User.create(email, passwordEncoder.encode(PASSWORD), Role.PATIENT));
    }

    // ---------- Cambio de contraseña (RF-IA-11) ----------

    @Test
    void change_CambiaHashYRevocaLasFamiliasActivas() {
        User user = createActive("ana@clinica.com");
        IssuedSession first = sessionService.login("ana@clinica.com", PASSWORD);
        IssuedSession second = sessionService.login("ana@clinica.com", PASSWORD);

        passwordService.change(user.getId(), PASSWORD, NEW_PASSWORD);

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(passwordEncoder.matches(NEW_PASSWORD, reloaded.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(PASSWORD, reloaded.getPasswordHash())).isFalse();

        // Ningun refresh emitido antes del cambio puede renovar (ninguna lista negra de access).
        assertThatThrownBy(() -> sessionService.refresh(first.refreshToken()))
                .isInstanceOf(NeutralAuthenticationException.class);
        assertThatThrownBy(() -> sessionService.refresh(second.refreshToken()))
                .isInstanceOf(NeutralAuthenticationException.class);
    }

    @Test
    void change_SinContrasenaActual_BadRequest() {
        User user = createActive("ana@clinica.com");
        assertThatThrownBy(() -> passwordService.change(user.getId(), "  ", NEW_PASSWORD))
                .isInstanceOf(MissingCurrentPasswordException.class);
        assertThatThrownBy(() -> passwordService.change(user.getId(), null, NEW_PASSWORD))
                .isInstanceOf(MissingCurrentPasswordException.class);
    }

    @Test
    void change_ContrasenaActualErronea_Rechaza() {
        User user = createActive("ana@clinica.com");
        assertThatThrownBy(() -> passwordService.change(user.getId(), "equivalente", NEW_PASSWORD))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void change_NuevaDeMasDe72Bytes_Rechaza() {
        User user = createActive("ana@clinica.com");
        assertThatThrownBy(() -> passwordService.change(user.getId(), PASSWORD, "a".repeat(73)))
                .isInstanceOf(PasswordExceedsHashingLimitException.class);
        assertThatThrownBy(() -> passwordService.change(user.getId(), "a".repeat(73), NEW_PASSWORD))
                .isInstanceOf(PasswordExceedsHashingLimitException.class);
    }

    // ---------- Recuperación (RF-IA-12) ----------

    @Test
    void requestReset_EmiteTokenParaCuentaActivaExistente_SinExponerlo() {
        createActive("ana@clinica.com");

        passwordService.requestReset("Ana@Clinica.com");

        List<PasswordResetToken> all = resetTokenRepository.findAll();
        assertThat(all).hasSize(1);
        PasswordResetToken token = all.get(0);
        assertThat(token.getExpiresAt())
                .isEqualTo(TestTime.now().plus(properties.passwordReset().ttl()));
        // El raw no sale del servicio: jamas existe un portador fuera de este metodo.
        assertThat(token.getUsedAt()).isEmpty();
    }

    @Test
    void requestReset_NeutroCuandoNoExiste_CuentaInactiva() {
        passwordService.requestReset("nadie@clinica.com");
        assertThat(resetTokenRepository.findAll()).isEmpty();

        userRepository.saveAndFlush(User.createPendingActivation("pend@clinica.com",
                passwordEncoder.encode(PASSWORD), Role.DENTIST));
        passwordService.requestReset("pend@clinica.com");
        assertThat(resetTokenRepository.findAll()).isEmpty();
    }

    @Test
    void reset_Valido_CambiaPasswordYRevocaFamilias() {
        User user = createActive("ana@clinica.com");
        IssuedSession session = sessionService.login("ana@clinica.com", PASSWORD);
        String token = issueResetToken(user.getId(), "raw-reset-token");

        passwordService.reset(token, NEW_PASSWORD);

        assertThat(passwordEncoder.matches(NEW_PASSWORD, userRepository.findById(user.getId()).orElseThrow()
                .getPasswordHash())).isTrue();
        assertThat(resetTokenRepository.findByTokenHash(tokenHasher.hash(token)).orElseThrow().getUsedAt())
                .isPresent();
        // La recuperacion tambien rompe las sesiones activas.
        assertThatThrownBy(() -> sessionService.refresh(session.refreshToken()))
                .isInstanceOf(NeutralAuthenticationException.class);
    }

    @Test
    void reset_DeUnSoloUso() {
        User user = createActive("ana@clinica.com");
        String token = issueResetToken(user.getId(), "raw-single-use");

        passwordService.reset(token, NEW_PASSWORD);
        assertThatThrownBy(() -> passwordService.reset(token, "otra-clave-2028"))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void reset_TokenCaducado_Rechaza() {
        User user = createActive("ana@clinica.com");
        String token = issueResetToken(user.getId(), "raw-expired", Duration.ofHours(-1));

        assertThatThrownBy(() -> passwordService.reset(token, NEW_PASSWORD))
                .isInstanceOf(InvalidTokenException.class);
        assertThat(passwordEncoder.matches(PASSWORD, userRepository.findById(user.getId()).orElseThrow()
                .getPasswordHash())).isTrue();
    }

    @Test
    void reset_TokenDesconocido_Neutral() {
        assertThatThrownBy(() -> passwordService.reset("token-que-no-existe", NEW_PASSWORD))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void reset_NuevaDeMasDe72Bytes_Rechaza() {
        User user = createActive("ana@clinica.com");
        String token = issueResetToken(user.getId(), "raw-72-limite");
        assertThatThrownBy(() -> passwordService.reset(token, "á".repeat(37)))
                .isInstanceOf(PasswordExceedsHashingLimitException.class);
    }

    private String issueResetToken(Long userId, String rawToken) {
        return issueResetToken(userId, rawToken, properties.passwordReset().ttl());
    }

    private String issueResetToken(Long userId, String rawToken, Duration ttl) {
        String hash = tokenHasher.hash(rawToken);
        PasswordResetToken token = PasswordResetToken.issue(userId, hash, TestTime.now().plus(ttl));
        resetTokenRepository.saveAndFlush(token);
        return rawToken;
    }
}
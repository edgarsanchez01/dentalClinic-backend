package com.dentalclinic.identity.application;

import java.time.Clock;
import java.time.Instant;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.identity.domain.EmailCanonicalizer;
import com.dentalclinic.identity.domain.PasswordPolicy;
import com.dentalclinic.identity.domain.PasswordResetToken;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.domain.exception.InvalidCredentialsException;
import com.dentalclinic.identity.domain.exception.InvalidTokenException;
import com.dentalclinic.identity.domain.exception.MissingCurrentPasswordException;
import com.dentalclinic.identity.infrastructure.repository.PasswordResetTokenRepository;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;
import com.dentalclinic.identity.infrastructure.security.SecurityProperties;
import com.dentalclinic.identity.infrastructure.token.TokenHasher;

/**
 * Cambio y recuperación de contraseña.
 *
 * <p>RF-IA-11 (cambio autenticado), RF-IA-12 (recuperación por email con token hasheado y
 * expiración) y §7.7: los tokens se almacenan hasheados, son expirables y de un solo uso, y la
 * recuperación responde de forma <strong>neutra</strong>.
 *
 * <h2>Revocación de sesiones al cambiar la contraseña</h2>
 *
 * <p>Decisión técnica de seguridad adoptada en Fase 2: tras un cambio o un restablecimiento
 * válido se revocan <strong>todas</strong> las familias de refresh activas del usuario, de forma
 * transaccional. Sin eso, un refresh token robado sobreviviría hasta 30 días aunque la contraseña
 * se hubiera cambiado, y el cambio de contraseña no serviría como recuperación real.
 *
 * <p>Un access token ya emitido sigue siendo válido hasta que expira, porque el modelo es
 * stateless y no existe lista negra de access tokens: introducirla para resolver esto sería
 * inventar un mecanismo que la arquitectura no describe. El objetivo alcanzable y verificable es
 * que <strong>ningún refresh token emitido antes del cambio pueda emitir nuevos access
 * tokens</strong>.
 */
@Service
public class PasswordService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository resetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenHasher tokenHasher;
    private final SecurityProperties properties;
    private final SessionService sessionService;
    private final Clock clock;

    public PasswordService(UserRepository userRepository,
                           PasswordResetTokenRepository resetTokenRepository,
                           PasswordEncoder passwordEncoder,
                           TokenHasher tokenHasher,
                           SecurityProperties properties,
                           SessionService sessionService,
                           Clock clock) {
        this.userRepository = userRepository;
        this.resetTokenRepository = resetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenHasher = tokenHasher;
        this.properties = properties;
        this.sessionService = sessionService;
        this.clock = clock;
    }

    /**
     * Cambio de contraseña autenticado (RF-IA-11).
     *
     * <p>Exige la contraseña actual. Sin ella, cualquiera con un access token robado podría fijar
     * una contraseña nueva y quedarse con la cuenta.
     *
     * @throws MissingCurrentPasswordException si no se envía la contraseña actual
     * @throws InvalidCredentialsException si la contraseña actual no coincide
     */
    @Transactional
    public void change(Long userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId).orElseThrow(InvalidCredentialsException::new);
        if (currentPassword == null || currentPassword.isBlank()) {
            throw new MissingCurrentPasswordException();
        }
        PasswordPolicy.requireWithinHashingLimit(currentPassword);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        PasswordPolicy.requireWithinHashingLimit(newPassword);

        user.changePasswordHash(passwordEncoder.encode(newPassword));
        sessionService.revokeAllSessions(userId);
    }

    /**
     * Solicita un token de recuperación (RF-IA-12).
     *
     * <p>La respuesta es <strong>neutra</strong>: si el email no existe, si la cuenta está
     * desactivada o si el token se emite, el resultado observable para el cliente es el mismo. La
     * comprobación de existencia ocurre igualmente —hay que saber a quién emitirle el token— pero
     * nunca cambia el status, ni el mensaje, ni el tiempo de respuesta de forma observable.
     *
     * <p>El token en claro <strong>no sale de este servicio</strong>: no se devuelve, no se registra
     * y no viaja en la respuesta. No es una precaución estética, es la razón por la que un token
     * de recuperación no puede filtrarse por un log. En Fase 2 no existe canal de entrega (las
     * comunicaciones por email son Fase 8), de modo que el enlace no llega a nadie todavía: el
     * token queda persistido solo como hash y expira por sí solo. Los tests del flujo de
     * restablecimiento crean el token por el repositorio, nunca por este método.
     */
    @Transactional
    public void requestReset(String rawEmail) {
        Instant now = clock.instant();
        String canonicalEmail = EmailCanonicalizer.canonicalize(rawEmail);

        var user = userRepository.findByEmail(canonicalEmail).orElse(null);
        if (user == null || !user.isActive()) {
            return;
        }

        String rawToken = tokenHasher.generateOpaqueToken();
        resetTokenRepository.save(PasswordResetToken.issue(
                user.getId(), tokenHasher.hash(rawToken), now.plus(properties.passwordReset().ttl())));
    }

    /**
     * Restablece la contraseña con un token de un solo uso (RF-IA-12).
     *
     * <p>El consumo es atómico por la misma razón que en las invitaciones: dos peticiones
     * simultáneas con el mismo token no pueden validarse ambas.
     */
    @Transactional
    public void reset(String rawToken, String newPassword) {
        PasswordPolicy.requireWithinHashingLimit(newPassword);
        Instant now = clock.instant();
        String tokenHash = tokenHasher.hash(rawToken);

        PasswordResetToken token = resetTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(InvalidTokenException::new);
        if (!token.isUsableAt(now)) {
            throw new InvalidTokenException();
        }

        User user = userRepository.findById(token.getUserId()).orElseThrow(InvalidTokenException::new);
        user.changePasswordHash(passwordEncoder.encode(newPassword));
        if (resetTokenRepository.consumeAtomically(tokenHash, now) != 1) {
            throw new InvalidTokenException();
        }
        sessionService.revokeAllSessions(user.getId());
    }
}
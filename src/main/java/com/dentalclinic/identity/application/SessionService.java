package com.dentalclinic.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.identity.domain.EmailCanonicalizer;
import com.dentalclinic.identity.domain.PasswordPolicy;
import com.dentalclinic.identity.domain.RefreshSession;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.domain.exception.AccountInactiveException;
import com.dentalclinic.identity.domain.exception.InvalidCredentialsException;
import com.dentalclinic.identity.domain.exception.NeutralAuthenticationException;
import com.dentalclinic.identity.infrastructure.repository.RefreshSessionRepository;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;
import com.dentalclinic.identity.infrastructure.security.JwtTokenService;
import com.dentalclinic.identity.infrastructure.security.SecurityProperties;
import com.dentalclinic.identity.infrastructure.token.TokenHasher;

/**
 * Emisión, rotación y revocación de sesiones.
 *
 * <p>Implementa el comportamiento observable de P-02 (CLOSED) y §7.1:
 *
 * <ul>
 *   <li>un login abre una <strong>familia</strong>; N sesiones independientes por usuario;</li>
 *   <li>el refresh es rotativo en <em>cada</em> uso, con ventana deslizante de 30 días;</li>
 *   <li>reutilizar un token ya rotado <strong>revoca la familia completa</strong> y responde de
 *       forma neutra;</li>
 *   <li>logout revoca <strong>solo la familia actual</strong>;</li>
 *   <li>{@code user.active = false} invalida las familias activas.</li>
 * </ul>
 */
@Service
public class SessionService {

    /**
     * Hash señuelo para igualar el coste de una autenticación fallida.
     *
     * <p>Si el email no existe y devolviéramos de inmediato, el tiempo de respuesta revelaría qué
     * correos están registrados. Computar un BCrypt igualmente contra este hash hace que el fallo
     * por email inexistente y el fallo por contraseña incorrecta cuesten lo mismo.
     *
     * <p>El hash es un BCrypt válido y verificado de una cadena aleatoria de 32 bytes que solo
     * existió en el proceso que la generó: no corresponde a ninguna contraseña conocida, pero su
     * estructura es legítima para que el comparador ejecute el mismo trabajo.
     */
    private static final String DUMMY_HASH = "$2a$10$pFBWh2ngxk/ril5ffoPKp.7AuzxqM6ycv.yNfjEZPVnX7CBUHLldu";

    private final UserRepository userRepository;
    private final RefreshSessionRepository refreshSessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final TokenHasher tokenHasher;
    private final SecurityProperties properties;
    private final Clock clock;

    public SessionService(UserRepository userRepository,
                          RefreshSessionRepository refreshSessionRepository,
                          PasswordEncoder passwordEncoder,
                          JwtTokenService jwtTokenService,
                          TokenHasher tokenHasher,
                          SecurityProperties properties,
                          Clock clock) {
        this.userRepository = userRepository;
        this.refreshSessionRepository = refreshSessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.tokenHasher = tokenHasher;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Autentica y abre una familia de sesión nueva.
     *
     * <p>El orden de las comprobaciones es intencionado:
     *
     * <ol>
     *   <li><strong>Contraseña primero.</strong> Comprobar {@code active} antes de validar la
     *       contraseña convertiría la respuesta "tu cuenta no está activada" en un oráculo que
     *       revela qué correos existen y están pendientes. Validando la contraseña primero, un
     *       atacante sin la contraseña siempre recibe el mismo mensaje genérico, y quien sí la
     *       conoce sí recibe la indicación útil de que debe activar su cuenta.</li>
     *   <li><strong>Límite de 72 bytes antes que {@code matches()}.</strong> BCrypt no aplica ese
     *       límite al comparar: una contraseña de 73 bytes puede devolver {@code true} contra un
     *       hash de 72 porque solo se compara el prefijo. Verificar antes cierra esa vía.</li>
     * </ol>
     */
    @Transactional
    public IssuedSession login(String rawEmail, String rawPassword) {
        Instant now = clock.instant();
        User user = userRepository.findByEmail(EmailCanonicalizer.canonicalize(rawEmail)).orElse(null);

        if (user == null) {
            passwordEncoder.matches(rawPassword, DUMMY_HASH);
            throw new InvalidCredentialsException();
        }

        PasswordPolicy.requireWithinHashingLimit(rawPassword);
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        if (!user.isActive()) {
            throw new AccountInactiveException();
        }
        return openFamily(user, now);
    }

    /**
     * Rota el refresh token.
     *
     * <p>El consumo es un único compare-and-set: dos peticiones simultáneas con el mismo token
     * hacen que solo una gane, y la otra se interpreta como reutilización.
     *
     * <p>{@code noRollbackFor} es deliberado: la respuesta neutra es un <em>resultado</em>
     * normal, no un fallo que deba deshacer trabajo. Antes de lanzarla se revoca la familia
     * (reuse detection) o el usuario inactivo corta sus sesiones, y esa revocación
     * <strong>tiene que persistir</strong>. Con el rollback por defecto de las excepciones no
     * comprobadas, la familia quedaba "revocada" solo en memoria y el siguiente refresh la veía
     * viva otra vez.
     *
     * @throws NeutralAuthenticationException siempre con la misma respuesta, tanto si el token
     *     está reutilizado, caducado, revocado o nunca existió (§7.7)
     */
    @Transactional(noRollbackFor = NeutralAuthenticationException.class)
    public IssuedSession refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new NeutralAuthenticationException();
        }
        Instant now = clock.instant();
        String presentedHash = tokenHasher.hash(rawRefreshToken);

        RefreshSession session = refreshSessionRepository.findByTokenHash(presentedHash)
                .orElseThrow(NeutralAuthenticationException::new);

        // El usuario se comprueba antes de consumir: una cuenta desactivada no renueva nada.
        User user = userRepository.findById(session.getUserId())
                .orElseThrow(() -> revokeAndFail(session, now));
        if (!user.isActive()) {
            throw revokeAndFail(session, now);
        }

        if (refreshSessionRepository.consumeAtomically(presentedHash, now) != 1) {
            // 0 filas: el token no era el vigente. Si ya estaba rotado, es un robo de token y se
            // cae la familia completa.
            if (refreshSessionRepository.wasAlreadyRotated(presentedHash)) {
                refreshSessionRepository.revokeFamily(session.getFamilyId(), now);
            }
            throw new NeutralAuthenticationException();
        }

        Duration ttl = properties.refresh().ttl();
        String nextRawToken = tokenHasher.generateOpaqueToken();
        refreshSessionRepository.save(RefreshSession.rotate(
                user.getId(), session.getFamilyId(), tokenHasher.hash(nextRawToken), now, now.plus(ttl)));

        var access = jwtTokenService.issue(user, now);
        return new IssuedSession(user, access.value(), access.expiresAt(),
                nextRawToken, now.plus(ttl), session.getFamilyId());
    }

    /**
     * Logout: revoca la familia actual y solo esa.
     *
     * <p>Es idempotente a propósito. Un token desconocido, ya revocado o de otro navegador no son
     * un error de autorización: la sesión ya no sirve para nada y el cliente quiereVerse libre.
     */
    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        Instant now = clock.instant();
        refreshSessionRepository.findByTokenHash(tokenHasher.hash(rawRefreshToken))
                .ifPresent(session -> refreshSessionRepository.revokeFamily(session.getFamilyId(), now));
    }

    /**
     * Revoca todas las familias activas de un usuario.
     *
     * <p>Se invoca tras un cambio o restablecimiento de contraseña. Es la razón por la que el
     * cambio de contraseña sirve como recuperación real: un refresh token robado deja de poder
     * emitir access tokens en cuanto la contraseña cambia. Un access token ya emitido sobrevive
     * como máximo su vida de 15 minutos, porque el modelo es stateless y no hay lista negra de
     * access tokens.
     */
    @Transactional
    public void revokeAllSessions(Long userId) {
        refreshSessionRepository.revokeAllForUser(userId, clock.instant());
    }

    private IssuedSession openFamily(User user, Instant now) {
        Duration ttl = properties.refresh().ttl();
        String rawToken = tokenHasher.generateOpaqueToken();
        RefreshSession session = refreshSessionRepository.save(
                RefreshSession.startFamily(user.getId(), tokenHasher.hash(rawToken), now, now.plus(ttl)));

        var access = jwtTokenService.issue(user, now);
        return new IssuedSession(user, access.value(), access.expiresAt(),
                rawToken, now.plus(ttl), session.getFamilyId());
    }

    private NeutralAuthenticationException revokeAndFail(RefreshSession session, Instant now) {
        refreshSessionRepository.revokeFamily(session.getFamilyId(), now);
        return new NeutralAuthenticationException();
    }
}
package com.dentalclinic.identity.api;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dentalclinic.identity.api.dto.ActivateRequest;
import com.dentalclinic.identity.api.dto.ChangePasswordRequest;
import com.dentalclinic.identity.api.dto.ForgotPasswordRequest;
import com.dentalclinic.identity.api.dto.LoginRequest;
import com.dentalclinic.identity.api.dto.LoginResponse;
import com.dentalclinic.identity.api.dto.RegisterRequest;
import com.dentalclinic.identity.api.dto.ResetPasswordRequest;
import com.dentalclinic.identity.api.dto.TokensResponse;
import com.dentalclinic.identity.api.dto.UserDto;
import com.dentalclinic.identity.application.InvitationActivationService;
import com.dentalclinic.identity.application.IssuedSession;
import com.dentalclinic.identity.application.PasswordService;
import com.dentalclinic.identity.application.IdentityRequestLimiter;
import com.dentalclinic.identity.infrastructure.security.IdentityClientAddress;
import com.dentalclinic.identity.application.ProfileService;
import com.dentalclinic.identity.application.ProfileService.UserProfile;
import com.dentalclinic.identity.application.RegistrationService;
import com.dentalclinic.identity.application.RegistrationService.RegistrationData;
import com.dentalclinic.identity.application.SessionService;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.identity.infrastructure.security.CurrentUser;
import com.dentalclinic.identity.infrastructure.security.RefreshCookieFactory;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ResponseStatus;
import jakarta.validation.Valid;

/**
 * Endpoints públicos y autenticados del módulo de identidad.
 *
 * <p>La <strong>allowlist</strong> de estas rutas vive en {@code SecurityConfig} (públicas) y el
 * resto queda autenticado por defecto (Architecture.md §7.3). El consumo de la cookie y el CSRF no
 * se resuelven aquí: los gestiona la cadena de seguridad.
 *
 * <p>Los métodos de rutas de escritura son {@code POST} con token CSRF para las que dependen de
 * cookie ({@code refresh}, {@code logout}) y sin {@code X-XSRF-TOKEN} para el resto.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final SessionService sessionService;
    private final InvitationActivationService invitationActivationService;
    private final PasswordService passwordService;
    private final ProfileService profileService;
    private final RegistrationService registrationService;
    private final RefreshCookieFactory refreshCookieFactory;
    private final Clock clock;
    private final IdentityRequestLimiter identityLimiter;
    private final IdentityClientAddress clientAddress;
    private final com.dentalclinic.patient.application.PatientService patients;

    public AuthController(SessionService sessionService,
                          InvitationActivationService invitationActivationService,
                          PasswordService passwordService,
                          ProfileService profileService,
                          RegistrationService registrationService,
                          RefreshCookieFactory refreshCookieFactory,
                           Clock clock, IdentityRequestLimiter identityLimiter, IdentityClientAddress clientAddress,
                           com.dentalclinic.patient.application.PatientService patients) {
        this.sessionService = sessionService;
        this.invitationActivationService = invitationActivationService;
        this.passwordService = passwordService;
        this.profileService = profileService;
        this.registrationService = registrationService;
        this.refreshCookieFactory = refreshCookieFactory;
        this.clock = clock;
        this.identityLimiter = identityLimiter;
        this.clientAddress = clientAddress;
        this.patients = patients;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest) {
        IssuedSession session = sessionService.login(request.email(), request.password());
        return sessionResponse(session, HttpStatus.OK);
    }

    /**
     * Registro público: User + Patient + sesión atómicos. Mismo contrato seguro que login,
     * con 201; /me sigue siendo la fuente autoritativa de perfil y onboarding.
     */
    @PostMapping("/register")
    public ResponseEntity<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        IssuedSession session = registrationService.register(new RegistrationData(
                request.email(), request.password(), request.firstName(), request.lastName(),
                request.phone(), request.birthDate()));
        return sessionResponse(session, HttpStatus.CREATED);
    }

    /**
     * Consumo de invitación. Responde <strong>204</strong>: no abre sesión —el flujo de la
     * invitación termina en "cuenta activa", y entrar pasa por {@code /login} de forma explícita
     * (flujo de P-03).
     */
    @PostMapping("/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void activate(@Valid @RequestBody ActivateRequest request) {
        invitationActivationService.activate(request.token(), request.password());
    }

    /** Recuperación: neutra (nunca revela si el email existe) y asíncrona (202). */
    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgot(@Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest httpRequest) {
        try {
            if (identityLimiter.allowResetIp(clientAddress.resolve(httpRequest))) {
                passwordService.requestReset(request.email());
            }
        } catch (RuntimeException ex) {
            // El proxy transaccional del servicio ya hizo rollback. Mismo 202 incluso ante fallo interno;
            // no registrar el mensaje/causa/entrada ni distinguir existencia de la cuenta.
            log.warn("No se pudo procesar la recuperación de identidad: {}", ex.getClass().getSimpleName());
        }
    }

    /** Restablecimiento con token de un solo uso. */
    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@Valid @RequestBody ResetPasswordRequest request) {
        passwordService.reset(request.token(), request.newPassword());
    }

    /** Cambio de contraseña autenticado (RF-IA-11). */
    @PostMapping("/password/change")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void change(@Valid @RequestBody ChangePasswordRequest request) {
        AuthenticatedUser principal = CurrentUser.require();
        passwordService.change(principal.userId(), request.currentPassword(), request.newPassword());
    }

    /** Rota el refresh; la cookie original queda invalidada y se escribe una nueva. */
    @PostMapping("/refresh")
    public ResponseEntity<TokensResponse> refresh(HttpServletRequest httpRequest) {
        String presented = refreshCookieFactory.read(httpRequest.getCookies());
        IssuedSession session = sessionService.refresh(presented);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookieFactory.issue(session.refreshToken(), ttlOf(session)).toString())
                .body(new TokensResponse(session.accessToken(), "Bearer", session.accessTokenExpiresAt()));
    }

    /** Logout: revoca la familia en el servidor e invalida la cookie. Idempotente. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        String presented = refreshCookieFactory.read(httpRequest.getCookies());
        sessionService.logout(presented);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE,
                refreshCookieFactory.expire().toString()).build();
    }

    /** Fuente autoritativa de la sesión (RF-IA-15, §14.3); incluye {@code patientId} si existe. */
    @GetMapping("/me")
    public ResponseEntity<UserDto> me() {
        AuthenticatedUser principal = CurrentUser.require();
        UserProfile profile = profileService.profileOf(principal.userId());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(new UserDto(
                profile.userId(), profile.email(), profile.role(), profile.active(), profile.createdAt(),
                profile.patientId(), profile.dentistId(), profile.role() == com.dentalclinic.identity.domain.Role.PATIENT
                        ? patients.medicalOnboardingFor(principal.userId()).orElse(null) : null));
    }

    private UserDto toDto(User user) {
        return new UserDto(user.getId(), user.getEmail(), user.getRole(), user.isActive(),
                user.getCreatedAt(), null, null);
    }

    private ResponseEntity<LoginResponse> sessionResponse(IssuedSession session, HttpStatus status) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookieFactory.issue(session.refreshToken(), ttlOf(session)).toString())
                .body(new LoginResponse(session.accessToken(), "Bearer",
                        session.accessTokenExpiresAt(), toDto(session.user())));
    }

    private Duration ttlOf(IssuedSession session) {
        return Duration.between(clock.instant(), session.refreshTokenExpiresAt());
    }
}

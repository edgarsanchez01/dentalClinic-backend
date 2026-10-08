package com.dentalclinic.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.dentalclinic.identity.IdentityIntegrationTestSupport;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.domain.Invitation;
import com.dentalclinic.identity.domain.PasswordResetToken;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.infrastructure.repository.InvitationRepository;
import com.dentalclinic.identity.infrastructure.repository.PasswordResetTokenRepository;
import com.dentalclinic.identity.infrastructure.repository.RefreshSessionRepository;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;
import com.dentalclinic.identity.infrastructure.security.SecurityProperties;
import com.dentalclinic.identity.infrastructure.token.TokenHasher;

import jakarta.servlet.http.Cookie;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * La cadena completa por HTTP: allowlist pública cerrada, 401/403/404 según §8.2 y §7.4,
 * CSRF solo en las rutas que dependen de cookie, y el contrato de la cookie del refresh (P-01/P-02).
 */
@AutoConfigureMockMvc
class AuthApiIntegrationTest extends IdentityIntegrationTestSupport {

    private static final String PASSWORD = "ClaveApi-2026";
    private static final String REFRESH_COOKIE = "dentalclinic_refresh";
    private static final String XSRF_COOKIE = "XSRF-TOKEN";
    private static final String XSRF_HEADER = "X-XSRF-TOKEN";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private InvitationRepository invitationRepository;
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

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @BeforeEach
    void cleanSlate() {
        TestTime.reset();
        refreshSessionRepository.deleteAll();
        resetTokenRepository.deleteAll();
        invitationRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ------------------------------------------------------------------ login

    @Test
    void login_devuelvePerfilYTokens_CookieConContrato() throws Exception {
        String email = seedUser("ana", Role.PATIENT, true, PASSWORD);
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
        {"email":"%s","password":"%s"}
        """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.id").isNumber())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.role").value("PATIENT"))
                .andExpect(jsonPath("$.user.active").value(true))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.user.patientId").doesNotExist())
                .andExpect(cookie().exists(REFRESH_COOKIE))
                .andExpect(cookie().httpOnly(REFRESH_COOKIE, true))
                .andExpect(cookie().path(REFRESH_COOKIE, "/api/auth"))
                .andExpect(cookie().secure(REFRESH_COOKIE, false))
                .andExpect(cookie().attribute(REFRESH_COOKIE, "SameSite", "Lax"))
                .andExpect(cookie().exists(XSRF_COOKIE))
                .andReturn();

        assertThatCookieExists(result.getResponse(), REFRESH_COOKIE);
    }

    @Test
    void login_ConEspaciosYMaysculasEnElEmail_IgualQueAntes() throws Exception {
        String email = seedUser("ana", Role.PATIENT, true, PASSWORD);
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
        {"email":"  %s  ","password":"%s"}
        """.formatted(email.toUpperCase(), PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void login_EmailDesconocidoYOContrasenaMala_401ConLaMismaForma() throws Exception {
        String email = seedUser("ana", Role.PATIENT, true, PASSWORD);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
        {"email":"no@existe.com","password":"%s"}
        """.formatted(PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
        {"email":"%s","password":"mala"}
        """.formatted(email)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void login_CuentaInactiva_401() throws Exception {
        String email = seedUser("pend", Role.DENTIST, false, PASSWORD);
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
        {"email":"%s","password":"%s"}
        """.formatted(email, PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_Validacion_400ConFieldErrors() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
        {"email":"no-es-un-email","password":""}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    // ------------------------------------------------------------------ /me

    @Test
    void me_SinToken_401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void me_ConToken_200SinPatientId() throws Exception {
        String email = seedUser("ana", Role.PATIENT, true, PASSWORD);
        String accessToken = loginAccessToken(email);

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("PATIENT"))
                .andExpect(jsonPath("$.patientId").doesNotExist());
    }

    @Test
    void me_TokenAlterado_401() throws Exception {
        String email = seedUser("ana", Role.PATIENT, true, PASSWORD);
        String accessToken = loginAccessToken(email);
        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + accessToken + "x"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * B5: editar el perfil (incluido el rol) no está en Fase 4. La ruta solo admite GET, así que
     * PATCH debe ser 405 — nunca 500 por el handler genérico, y nunca una via de escalada.
     */
    @Test
    void me_MetodoNoSoportado_405() throws Exception {
        String email = seedUser("ana", Role.PATIENT, true, PASSWORD);
        String accessToken = loginAccessToken(email);
        mockMvc.perform(patch("/api/auth/me")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"OWNER"}
                                """))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void killSwitch_CuentaDesactivada_CortaElAccesoEnLaSiguientePeticion() throws Exception {
        User user = seed("ana", Role.PATIENT, true, PASSWORD);
        String accessToken = loginAccessToken(user.getEmail());

        user.deactivate();
        userRepository.saveAndFlush(user);

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ activate

    @Test
    void activate_CreaCuentaActiva_204_YSegundaVez_401() throws Exception {
        String email = "paciente@clinica.com";
        String token = seedInvitation(email, Role.PATIENT);

        mockMvc.perform(post("/api/auth/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
        {"token":"%s","password":"%s"}
        """.formatted(token, PASSWORD)))
                .andExpect(status().isNoContent());

        User user = userRepository.findByEmail(email).orElseThrow();
        assertThat(user.isActive()).isTrue();

        mockMvc.perform(post("/api/auth/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
        {"token":"%s","password":"otra"}
        """.formatted(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void activate_TokenCaducado_401() throws Exception {
        String email = "caduco@clinica.com";
        String token = seedInvitation(email, Role.PATIENT, Duration.ofHours(-1));
        mockMvc.perform(post("/api/auth/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
        {"token":"%s","password":"%s"}
        """.formatted(token, PASSWORD)))
                .andExpect(status().isUnauthorized());
        assertThat(userRepository.findByEmail(email)).isEmpty();
    }

    // ------------------------------------------------------------------ password

    @Test
    void forgot_NeutroParaEmailExistenteYDesconocido() throws Exception {
        seedUser("ana", Role.PATIENT, true, PASSWORD);

        mockMvc.perform(post("/api/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
        {"email":"ana@clinica.com"}"""))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/auth/password/forgot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
        {"email":"nadie@clinica.com"}"""))
                .andExpect(status().isAccepted());

        assertThat(resetTokenRepository.findAll().size()).isEqualTo(1);
    }

    @Test
    void reset_204_YMetaSoloUso_401EnRepetir() throws Exception {
        User user = seed("reset@clinica.com", Role.PATIENT, true, PASSWORD);
        String token = "raw-reset-" + user.getId();
        resetTokenRepository.saveAndFlush(PasswordResetToken.issue(user.getId(),
                tokenHasher.hash(token), TestTime.now().plus(properties.passwordReset().ttl())));

        mockMvc.perform(post("/api/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
        {"token":"%s","newPassword":"NuevaClave-2027"}
        """.formatted(token)))
                .andExpect(status().isNoContent());

        assertThat(passwordEncoder.matches("NuevaClave-2027",
                userRepository.findById(user.getId()).orElseThrow().getPasswordHash())).isTrue();

        mockMvc.perform(post("/api/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
        {"token":"%s","newPassword":"OtraMas-2028"}
        """.formatted(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changeConSesion_respuesta204_YSinAcceso_401() throws Exception {
        String email = seedUser("cambia@clinica.com", Role.PATIENT, true, PASSWORD);
        String accessToken = loginAccessToken(email);

        mockMvc.perform(post("/api/auth/password/change")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
        {"currentPassword":"%s","newPassword":"Nueva-2027"}
        """.formatted(PASSWORD)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/password/change")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
        {"currentPassword":"%s","newPassword":"Nueva-2027"}
        """.formatted(PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void change_ContrasenaActualAusente_400() throws Exception {
        String email = seed("cambia@clinica.com", Role.PATIENT, true, PASSWORD).getEmail();
        String accessToken = loginAccessToken(email);
        mockMvc.perform(post("/api/auth/password/change")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
        {"newPassword":"Nueva-2027"}
        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void change_ContrasenaActualErronea_401() throws Exception {
        String email = seed("cambia@clinica.com", Role.PATIENT, true, PASSWORD).getEmail();
        String accessToken = loginAccessToken(email);
        mockMvc.perform(post("/api/auth/password/change")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
        {"currentPassword":"no-es-esta","newPassword":"Nueva-2027"}
        """))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ refresh / logout

    @Test
    void refresh_RotaYCookieEsReemplazada() throws Exception {
        LoggedIn logged = login(seedUser("r@c.com", Role.PATIENT, true, PASSWORD));

        MvcResult result = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(logged.refreshCookie(), logged.xsrfCookie())
                        .header(XSRF_HEADER, logged.xsrfValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(cookie().exists(REFRESH_COOKIE))
                .andReturn();

        String newRefresh = result.getResponse().getCookie(REFRESH_COOKIE).getValue();
        assertThat(newRefresh).isNotEqualTo(logged.refreshValue());

        // El token viejo ya no renueva: es reutilizacion.
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(logged.refreshCookie(), logged.xsrfCookie())
                        .header(XSRF_HEADER, logged.xsrfValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_SinCookie_401() throws Exception {
        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_ConCookiePeroSinTokenCsrf_403() throws Exception {
        LoggedIn logged = login(seedUser("csrf@c.com", Role.PATIENT, true, PASSWORD));
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(logged.refreshCookie(), logged.xsrfCookie()))
                .andExpect(status().isForbidden());
    }

    @Test
    void refresh_Caducado_401() throws Exception {
        LoggedIn logged = login(seedUser("vejez@c.com", Role.PATIENT, true, PASSWORD));
        TestTime.advance(Duration.ofDays(31));
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(logged.refreshCookie(), logged.xsrfCookie())
                        .header(XSRF_HEADER, logged.xsrfValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_RevocaDesdeElServidorY_CookieExpira() throws Exception {
        LoggedIn logged = login(seedUser("sale@c.com", Role.PATIENT, true, PASSWORD));

        mockMvc.perform(post("/api/auth/logout")
                        .cookie(logged.refreshCookie(), logged.xsrfCookie())
                        .header(XSRF_HEADER, logged.xsrfValue()))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge(REFRESH_COOKIE, 0));

        // La familia quedo revocada en el servidor: renovar ya no devuelve tokens.
        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(logged.refreshCookie(), logged.xsrfCookie())
                        .header(XSRF_HEADER, logged.xsrfValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logout_SinCookie_204Idempotente() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------ allowlist y cierre

    @Test
    void rutaDeNegocioFueraDeLaMatriz_ConSesionValida_403() throws Exception {
        String email = seedUser("dueño@c.com", Role.OWNER, true, PASSWORD);
        String accessToken = loginAccessToken(email);
        mockMvc.perform(get("/api/nada/que/no/este/en/matriz")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void refreshConLaCookieDelRefresh_noSustituyeAlBearerParaElResto() throws Exception {
        LoggedIn logged = login(seedUser("cookie-only@c.com", Role.PATIENT, true, PASSWORD));
        // La cookie es HttpOnly y Path=/api/auth: /me no la acepta como credencial.
        mockMvc.perform(get("/api/auth/me").cookie(logged.refreshCookie()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cors_OrigenPermitidoDevuelveCabeceras_OrigenAjenoNo() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));

        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void errorForma_CumpleElContrato() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(not(blankOrNullString())))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.path").value("/api/auth/me"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    // ------------------------------------------------------------------ helpers

    private String loginAccessToken(String email) throws Exception {
        MvcResult result = login(email).result();
        return asJson(result).get("accessToken").asText();
    }

    private MvcResult loginRaw(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
        {"email":"%s","password":"%s"}
        """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private LoggedIn login(String email) throws Exception {
        MvcResult result = loginRaw(email);
        MockHttpServletResponse response = result.getResponse();
        return new LoggedIn(
                new Cookie(REFRESH_COOKIE, response.getCookie(REFRESH_COOKIE).getValue()),
                new Cookie(XSRF_COOKIE, response.getCookie(XSRF_COOKIE).getValue()),
                response.getCookie(REFRESH_COOKIE).getValue(),
                response.getCookie(XSRF_COOKIE).getValue(),
                result);
    }

    private com.fasterxml.jackson.databind.JsonNode asJson(MvcResult result) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString());
    }

    private record LoggedIn(Cookie refreshCookie, Cookie xsrfCookie, String refreshValue,
            String xsrfValue, MvcResult result) {
    }

    private String seedInvitation(String email, Role role) {
        return seedInvitation(email, role, properties.invitation().ttl());
    }

    private String seedInvitation(String email, Role role, Duration ttl) {
        String token = "invite-" + email + "-" + COUNTER.incrementAndGet();
        invitationRepository.saveAndFlush(Invitation.issue(
                tokenHasher.hash(token), email, role, TestTime.now().plus(ttl)));
        return token;
    }

    private String seedUser(String nameOrEmail, Role role, boolean active, String password) {
        return seed(nameOrEmail, role, active, password).getEmail();
    }

    private User seed(String nameOrEmail, Role role, boolean active, String password) {
        String email = nameOrEmail.contains("@") ? nameOrEmail : nameOrEmail + "@clinica.com";
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = active ? User.create(email, passwordEncoder.encode(password), role)
                    : User.createPendingActivation(email, passwordEncoder.encode(password), role);
            return userRepository.saveAndFlush(user);
        });
    }

    private static void assertThatCookieExists(MockHttpServletResponse response, String name) {
        org.assertj.core.api.Assertions.assertThat(response.getCookie(name)).isNotNull();
    }
}
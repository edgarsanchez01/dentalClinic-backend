package com.dentalclinic.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.dentalclinic.identity.IdentityIntegrationTestSupport;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.infrastructure.repository.InvitationRepository;
import com.dentalclinic.identity.infrastructure.repository.PasswordResetTokenRepository;
import com.dentalclinic.identity.infrastructure.repository.RefreshSessionRepository;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;
import com.dentalclinic.patient.infrastructure.repository.PatientRepository;

/**
 * Registro publico de paciente (RF-IA-05/06): crea cuenta + paciente en una transaccion, nunca
 * acepta rol desde el cuerpo y es publico (sin token). Verifica ademas que {@code /me} resuelve el
 * {@code patientId} real (P-08, §14.3).
 */
@AutoConfigureMockMvc
class RegisterApiIntegrationTest extends IdentityIntegrationTestSupport {

    private static final String PASSWORD = "ClaveRegistro-2026";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PatientRepository patientRepository;
    @Autowired
    private InvitationRepository invitationRepository;
    @Autowired
    private PasswordResetTokenRepository resetTokenRepository;
    @Autowired
    private RefreshSessionRepository refreshSessionRepository;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper mapper;
    @Autowired private org.springframework.security.crypto.password.PasswordEncoder passwords;

    @BeforeEach
    void cleanSlate() {
        TestTime.reset();
        refreshSessionRepository.deleteAll();
        resetTokenRepository.deleteAll();
        invitationRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void register_creaCuentaActivaYPaciente_201_sinPasswordHash() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("ana@c.com", PASSWORD, "Ana", "Perez", "+34 600 111 222", "1990-05-01")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.id").isNumber())
                .andExpect(jsonPath("$.user.email").value("ana@c.com"))
                .andExpect(jsonPath("$.user.role").value("PATIENT"))
                .andExpect(jsonPath("$.user.active").value(true))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(cookie().httpOnly("dentalclinic_refresh", true))
                .andExpect(cookie().path("dentalclinic_refresh", "/api/auth"))
                .andExpect(cookie().attribute("dentalclinic_refresh", "SameSite", "Lax"))
                .andExpect(cookie().maxAge("dentalclinic_refresh", 2592000))
                .andExpect(cookie().exists("XSRF-TOKEN"));

        var user = userRepository.findByEmail("ana@c.com").orElseThrow();
        assertThat(user.getRole()).isEqualTo(Role.PATIENT);
        assertThat(user.isActive()).isTrue();
        assertThat(passwords.matches(PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(refreshSessionRepository.count()).isEqualTo(1);

        var patient = patientRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(patient.getFirstName()).isEqualTo("Ana");
        assertThat(patient.getLastName()).isEqualTo("Perez");
        assertThat(patient.getEmail()).as("Patient.email no copia el email de la cuenta").isNull();
        assertThat(patient.isActive()).isTrue();
        assertThat(patient.getBirthDate()).hasToString("1990-05-01");
    }

    @Test
    void register_nuncaAceptaRolDelCuerpo() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"jefa@c.com","password":"%s","firstName":"Jo","lastName":"Fa",
                                 "role":"OWNER"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.role").value("PATIENT"));
        assertThat(userRepository.findByEmail("jefa@c.com").orElseThrow().getRole()).isEqualTo(Role.PATIENT);
    }

    @Test
    void register_emailDuplicado_409() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("dup@c.com", PASSWORD, "A", "B", null, null)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("dup@c.com", PASSWORD, "A", "B", null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(cookie().doesNotExist("dentalclinic_refresh"));
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(patientRepository.count()).isEqualTo(1);
        assertThat(refreshSessionRepository.count()).isEqualTo(1);
    }

    @Test
    void register_validacion_400ConFieldErrors() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"no-es-un-email","password":"","firstName":"","lastName":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists())
                .andExpect(jsonPath("$.fieldErrors.firstName").exists())
                .andExpect(jsonPath("$.fieldErrors.lastName").exists());
    }

    @Test
    void register_passwordQueExcedeElLimite_400() throws Exception {
        String larga = "a".repeat(80);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("larga@c.com", larga, "A", "B", null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void me_incluyeElPatientIdRealDelPacienteRegistrado() throws Exception {
        MvcResult registered = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("me@c.com", PASSWORD, "Me", "Same", null, null)))
                .andExpect(status().isCreated())
                .andReturn();
        var payload = mapper.readTree(registered.getResponse().getContentAsString());
        long patientId = patientRepository.findByUserId(payload.at("/user/id").asLong()).orElseThrow().getId();
        String accessToken = payload.get("accessToken").asText();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("PATIENT"))
                .andExpect(jsonPath("$.patientId").value(patientId))
                .andExpect(jsonPath("$.medicalOnboarding.pending").value(true));
    }

    @Test
    void registroRenuevaConCsrfLogoutRevocaYLoginPosteriorFunciona() throws Exception {
        var registration = register("session@dc.test");
        var refresh = registration.getResponse().getCookie("dentalclinic_refresh");
        var xsrf = registration.getResponse().getCookie("XSRF-TOKEN");
        var stored = refreshSessionRepository.findAll().getFirst();
        assertThat(stored.getTokenHash()).isNotEqualTo(refresh.getValue()).hasSize(64);
        TestTime.advance(java.time.Duration.ofMinutes(16));
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token(registration)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh").cookie(refresh)).andExpect(status().isForbidden());
        var rotated = mockMvc.perform(post("/api/auth/refresh").cookie(refresh, xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isOk()).andExpect(cookie().exists("dentalclinic_refresh")).andReturn();
        var next = rotated.getResponse().getCookie("dentalclinic_refresh");
        assertThat(next.getValue()).isNotEqualTo(refresh.getValue());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token(rotated)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.medicalOnboarding.pending").value(true));
        mockMvc.perform(post("/api/auth/logout").cookie(next, xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isNoContent()).andExpect(cookie().maxAge("dentalclinic_refresh", 0));
        mockMvc.perform(post("/api/auth/refresh").cookie(next, xsrf).header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"session@dc.test\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    @Test
    void refreshDeRegistroExpiradoNoEmiteCredenciales() throws Exception {
        var registration = register("expired@dc.test");
        var xsrf = registration.getResponse().getCookie("XSRF-TOKEN");
        TestTime.advance(java.time.Duration.ofDays(31));
        mockMvc.perform(post("/api/auth/refresh").cookie(registration.getResponse().getCookie("dentalclinic_refresh"), xsrf)
                .header("X-XSRF-TOKEN", xsrf.getValue())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(cookie().doesNotExist("dentalclinic_refresh"));
        assertThat(refreshSessionRepository.count()).isEqualTo(1);
    }

    @Test
    void falloAlCrearSesionRevierteCuentaYPacienteSinCookie() throws Exception {
        // Falla DESPUÉS de los inserts de User/Patient, solo en el PostgreSQL efímero del test.
        jdbc.execute("CREATE FUNCTION reject_registration_session() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic session failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_registration_session BEFORE INSERT ON refresh_sessions FOR EACH ROW EXECUTE FUNCTION reject_registration_session()");
        try {
            mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                    .content(body("rollback@dc.test", PASSWORD, "Ficticio", "Rollback", null, null)))
                    .andExpect(status().is5xxServerError()).andExpect(cookie().doesNotExist("dentalclinic_refresh"));
            assertThat(userRepository.count()).isZero();
            assertThat(patientRepository.count()).isZero();
            assertThat(refreshSessionRepository.count()).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER reject_registration_session ON refresh_sessions");
            jdbc.execute("DROP FUNCTION reject_registration_session()");
        }
    }

    private MvcResult register(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(body(email, PASSWORD, "Ficticio", "Sesion", null, null)))
                .andExpect(status().isCreated()).andReturn();
    }

    private String token(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private static String body(String email, String password, String firstName, String lastName,
                               String phone, String birthDate) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("email", email);
        map.put("password", password);
        map.put("firstName", firstName);
        map.put("lastName", lastName);
        if (phone != null) {
            map.put("phone", phone);
        }
        if (birthDate != null) {
            map.put("birthDate", birthDate);
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}

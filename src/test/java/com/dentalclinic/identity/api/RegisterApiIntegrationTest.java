package com.dentalclinic.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.email").value("ana@c.com"))
                .andExpect(jsonPath("$.role").value("PATIENT"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.patientId").isNumber())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        var user = userRepository.findByEmail("ana@c.com").orElseThrow();
        assertThat(user.getRole()).isEqualTo(Role.PATIENT);
        assertThat(user.isActive()).isTrue();

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
                .andExpect(jsonPath("$.role").value("PATIENT"));
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
                .andExpect(jsonPath("$.status").value(409));
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
        long patientId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(registered.getResponse().getContentAsString()).get("patientId").asLong();

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"me@c.com","password":"%s"}
                                """.formatted(PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        String accessToken = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(login.getResponse().getContentAsString()).get("accessToken").asText();

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("PATIENT"))
                .andExpect(jsonPath("$.patientId").value(patientId));
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
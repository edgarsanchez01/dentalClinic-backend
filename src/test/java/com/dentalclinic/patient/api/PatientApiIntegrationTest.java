package com.dentalclinic.patient.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.infrastructure.repository.InvitationRepository;
import com.dentalclinic.identity.infrastructure.repository.PasswordResetTokenRepository;
import com.dentalclinic.identity.infrastructure.repository.RefreshSessionRepository;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;
import com.dentalclinic.patient.domain.Patient;
import com.dentalclinic.patient.infrastructure.repository.PatientRepository;

/**
 * Contrato HTTP de la gestion administrativa de pacientes (matriz §7.4).
 *
 * <p>OWNER/RECEPTIONIST gestionan, DENTIST solo lee, PATIENT solo se lee a si mismo y un recurso
 * ajeno responde 404 (nunca 403).
 */
@AutoConfigureMockMvc
class PatientApiIntegrationTest extends IdentityIntegrationTestSupport {

    private static final String PASSWORD = "ClavePaciente-2026";

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
    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanSlate() {
        TestTime.reset();
        refreshSessionRepository.deleteAll();
        resetTokenRepository.deleteAll();
        invitationRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void owner_listaConBusquedaYFiltroDeVigencia() throws Exception {
        String token = loginToken(seedUser("own@c.com", Role.OWNER));
        createPatient(token, patientBody("Ana", "Garcia", "ana@c.com", "+34 600 1"));
        String inactiveId = createPatient(token, patientBody("Luis", "Soto", "luis@c.com", "+34 600 2"));

        mockMvc.perform(patch("/api/patients/" + inactiveId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Luis","lastName":"Soto","active":false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/api/patients").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/patients").param("search", "gar").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].lastName").value("Garcia"));

        mockMvc.perform(get("/api/patients").param("active", "false").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].firstName").value("Luis"));
    }

    @Test
    void dentist_soloLee_noPuedeCrear() throws Exception {
        String token = loginToken(seedUser("den@c.com", Role.DENTIST));
        mockMvc.perform(get("/api/patients").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/patients").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patientBody("No", "Puede", null, null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void receptionist_puedeCrearYEditar() throws Exception {
        String token = loginToken(seedUser("rec@c.com", Role.RECEPTIONIST));
        String id = createdId(mockMvc.perform(post("/api/patients").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patientBody("Rita", "Lopez", "rita@c.com", null)))
                .andExpect(status().isCreated())
                .andReturn());
        mockMvc.perform(patch("/api/patients/" + id).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Rita","lastName":"Lopez","phone":"+34 600 999","active":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("+34 600 999"));
    }

    @Test
    void patient_soloSeVeASiMismo_yNoEscribe() throws Exception {
        User patientUser = seedUser("pac@c.com", Role.PATIENT);
        Patient mine = patientRepository.saveAndFlush(Patient.createForUser(patientUser.getId(),
                "Ana", "Mia", "a@c.com", "600", null, null));
        Patient other = patientRepository.saveAndFlush(Patient.create("Otro", "Ajeno", null, null,
                null, null));

        String token = loginToken(patientUser);
        mockMvc.perform(get("/api/patients").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(mine.getId()));
        mockMvc.perform(get("/api/patients/" + mine.getId()).header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/patients/" + other.getId()).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/patients").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patientBody("P", "Q", null, null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void crearConDatosInvalidos_400ConFieldErrors() throws Exception {
        String token = loginToken(seedUser("own2@c.com", Role.OWNER));
        mockMvc.perform(post("/api/patients").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"","lastName":"","email":"no-es-email"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.firstName").exists())
                .andExpect(jsonPath("$.fieldErrors.lastName").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists());
    }

    @Test
    void sinAutenticacion_401() throws Exception {
        mockMvc.perform(get("/api/patients")).andExpect(status().isUnauthorized());
    }

    @Test
    void pacienteSinCuentaEsValido_userIdNulo() throws Exception {
        String token = loginToken(seedUser("own3@c.com", Role.OWNER));
        String id = createdId(mockMvc.perform(post("/api/patients").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patientBody("Sin", "Cuenta", null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").doesNotExist())
                .andReturn());
        assertThat(patientRepository.findById(Long.parseLong(id)).orElseThrow().getUserId()).isNull();
    }

    private String createPatient(String token, String body) throws Exception {
        return createdId(mockMvc.perform(post("/api/patients").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private String createdId(MvcResult result) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String loginToken(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(user.getEmail(), PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private User seedUser(String email, Role role) {
        return userRepository.findByEmail(email)
                .orElseGet(() -> userRepository.saveAndFlush(
                        User.create(email, passwordEncoder.encode(PASSWORD), role)));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String patientBody(String firstName, String lastName, String email, String phone) {
        StringBuilder sb = new StringBuilder("{\"firstName\":\"").append(firstName)
                .append("\",\"lastName\":\"").append(lastName).append("\"");
        if (email != null) {
            sb.append(",\"email\":\"").append(email).append("\"");
        }
        if (phone != null) {
            sb.append(",\"phone\":\"").append(phone).append("\"");
        }
        return sb.append("}").toString();
    }
}
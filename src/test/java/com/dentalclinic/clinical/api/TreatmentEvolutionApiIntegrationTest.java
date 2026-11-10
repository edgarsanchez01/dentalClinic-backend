package com.dentalclinic.clinical.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.dentalclinic.clinic.application.DentistService;
import com.dentalclinic.clinic.application.DentistService.DentistData;
import com.dentalclinic.clinic.infrastructure.repository.DentistRepository;
import com.dentalclinic.clinical.infrastructure.repository.TreatmentEvolutionRepository;
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
 * Contrato HTTP de la evolucion de un tratamiento (RF-TR-07, EVO-01, AD-26).
 *
 * <p>Group {@code /api/patients/{id}/clinical/treatments/{treatmentId}/evolution}: OWNER y DENTIST
 * leen y escriben (el OWNER siempre tiene perfil de dentista, AD-11); RECEPTIONIST y PATIENT no
 * tienen acceso. Las entradas son append-only: no hay {@code PUT}/{@code PATCH}/{@code DELETE}.
 */
@AutoConfigureMockMvc
class TreatmentEvolutionApiIntegrationTest extends IdentityIntegrationTestSupport {

    private static final String PASSWORD = "ClaveClinica-2026";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PatientRepository patientRepository;
    @Autowired
    private DentistRepository dentistRepository;
    @Autowired
    private DentistService dentistService;
    @Autowired
    private TreatmentEvolutionRepository treatmentEvolutionRepository;
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
        dentistRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void dentistaRegistraYListaEnOrdenCronologico_conAutor() throws Exception {
        String token = dentistToken("evo-doc@c.com");
        Long patientId = newPatient("Cli", "Ente");
        long treatmentId = createTreatment(token, patientId, "Endodoncia pieza 21");

        append(token, patientId, treatmentId, "Primera sesion: apertura cameral.")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("Primera sesion: apertura cameral."))
                .andExpect(jsonPath("$.authorName").value("Dora Dentista"))
                .andExpect(jsonPath("$.authorDentistId").exists())
                .andExpect(jsonPath("$.createdAt").exists());

        TestTime.advance(Duration.ofMinutes(1));
        append(token, patientId, treatmentId, "Segunda sesion: instrumentacion.")
                .andExpect(status().isCreated());

        mockMvc.perform(get(evolution(patientId, treatmentId))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].content").value("Primera sesion: apertura cameral."))
                .andExpect(jsonPath("$[1].content").value("Segunda sesion: instrumentacion."));

        assertThat(treatmentEvolutionRepository.count()).isEqualTo(2);
    }

    @Test
    void mismoInstante_seOrdenaPorIdDeFormaEstable() throws Exception {
        String token = dentistToken("evo-order@c.com");
        Long patientId = newPatient("Cli", "Ente");
        long treatmentId = createTreatment(token, patientId, "Obturacion");

        append(token, patientId, treatmentId, "Entrada A").andExpect(status().isCreated());
        append(token, patientId, treatmentId, "Entrada B").andExpect(status().isCreated());

        mockMvc.perform(get(evolution(patientId, treatmentId))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].content").value("Entrada A"))
                .andExpect(jsonPath("$[1].content").value("Entrada B"));
    }

    @Test
    void contenidoVacioOExcedido_400_yNoPersiste() throws Exception {
        String token = dentistToken("evo-invalid@c.com");
        Long patientId = newPatient("Cli", "Ente");
        long treatmentId = createTreatment(token, patientId, "Limpieza");

        append(token, patientId, treatmentId, "   ").andExpect(status().isBadRequest());
        append(token, patientId, treatmentId, "").andExpect(status().isBadRequest());
        append(token, patientId, treatmentId, "a".repeat(4001)).andExpect(status().isBadRequest());

        mockMvc.perform(post(evolution(patientId, treatmentId))
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(treatmentEvolutionRepository.count()).isZero();
    }

    @Test
    void aislamientoEntrePacientesYRutasInexistentes_404() throws Exception {
        String token = dentistToken("evo-iso@c.com");
        Long patientA = newPatient("Ana", "Propia");
        Long patientB = newPatient("Beto", "Ajena");
        long treatmentA = createTreatment(token, patientA, "Tratamiento de Ana");
        long treatmentB = createTreatment(token, patientB, "Tratamiento de Beto");
        append(token, patientA, treatmentA, "Nota de Ana").andExpect(status().isCreated());
        append(token, patientB, treatmentB, "Nota de Beto").andExpect(status().isCreated());

        // El tratamiento de A no es visible a traves del paciente B (se trata como inexistente).
        mockMvc.perform(get(evolution(patientB, treatmentA))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        append(token, patientB, treatmentA, "Intento cruzado").andExpect(status().isNotFound());

        // Rutas inexistentes.
        mockMvc.perform(get(evolution(patientA, 999999L))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(evolution(999999L, treatmentA))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());

        assertThat(treatmentEvolutionRepository.count()).isEqualTo(2);
    }

    @Test
    void matrizDePermisos_ownerYDentistEscriben_receptionistYPatient403_sinToken401()
            throws Exception {
        // DENTIST
        String dentistToken = dentistToken("evo-perm-doc@c.com");
        Long patientId = newPatient("Cli", "Ente");
        long treatmentId = createTreatment(dentistToken, patientId, "Obturacion");
        append(dentistToken, patientId, treatmentId, "Sesion de dentista").andExpect(status().isCreated());
        mockMvc.perform(get(evolution(patientId, treatmentId))
                        .header("Authorization", bearer(dentistToken)))
                .andExpect(status().isOk());

        // OWNER (siempre con perfil Dentist, AD-11)
        String ownerToken = ownerToken("evo-perm-own@c.com");
        Long ownerPatient = newPatient("Oli", "Owner");
        long ownerTreatment = createTreatment(ownerToken, ownerPatient, "Obturacion owner");
        append(ownerToken, ownerPatient, ownerTreatment, "Sesion de owner").andExpect(status().isCreated());
        mockMvc.perform(get(evolution(ownerPatient, ownerTreatment))
                        .header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk());

        // RECEPTIONIST: 403 en lectura y escritura.
        String receptionistToken = loginToken(seedUser("evo-perm-rec@c.com", Role.RECEPTIONIST));
        mockMvc.perform(get(evolution(patientId, treatmentId))
                        .header("Authorization", bearer(receptionistToken)))
                .andExpect(status().isForbidden());
        append(receptionistToken, patientId, treatmentId, "No autorizado").andExpect(status().isForbidden());

        // PATIENT: 403 incluso sobre su propio expediente.
        User patientUser = seedUser("evo-perm-pac@c.com", Role.PATIENT);
        Long selfPatientId = patientRepository.saveAndFlush(Patient.createForUser(patientUser.getId(),
                "Pac", "Uno", null, null, null, null)).getId();
        String patientToken = loginToken(patientUser);
        mockMvc.perform(get(evolution(selfPatientId, treatmentId))
                        .header("Authorization", bearer(patientToken)))
                .andExpect(status().isForbidden());

        // Sin token: 401.
        mockMvc.perform(get(evolution(patientId, treatmentId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void appendOnly_putPatchDelete_405() throws Exception {
        String token = dentistToken("evo-append@c.com");
        Long patientId = newPatient("Cli", "Ente");
        long treatmentId = createTreatment(token, patientId, "Endodoncia");
        append(token, patientId, treatmentId, "Nota original").andExpect(status().isCreated());

        String body = """
                {"content":"Intento de edicion"}
                """;
        mockMvc.perform(put(evolution(patientId, treatmentId))
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(patch(evolution(patientId, treatmentId))
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete(evolution(patientId, treatmentId))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isMethodNotAllowed());

        assertThat(treatmentEvolutionRepository.count()).isEqualTo(1);
    }

    private ResultActions append(String token, Long patientId, long treatmentId, String content)
            throws Exception {
        return mockMvc.perform(post(evolution(patientId, treatmentId))
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"content":%s}
                        """.formatted(jsonString(content))));
    }

    private long createTreatment(String token, Long patientId, String description) throws Exception {
        MvcResult created = mockMvc.perform(post(treatments(patientId))
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":%s}
                                """.formatted(jsonString(description))))
                .andExpect(status().isCreated())
                .andReturn();
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(created.getResponse().getContentAsString()).get("id").asLong();
    }

    private Long newPatient(String firstName, String lastName) {
        return patientRepository.saveAndFlush(
                Patient.create(firstName, lastName, null, null, null, null)).getId();
    }

    private String dentistToken(String email) throws Exception {
        User user = seedUser(email, Role.DENTIST);
        dentistService.createForUser(user.getId(), new DentistData("Dora", "Dentista", "600",
                "Ortodoncia", "LIC-1", null, null));
        return loginToken(user);
    }

    private String ownerToken(String email) throws Exception {
        User user = seedUser(email, Role.OWNER);
        dentistService.createForUser(user.getId(), new DentistData("Olivia", "Owner", "601",
                "General", "LIC-OWN", null, null));
        return loginToken(user);
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

    private static String jsonString(String value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String treatments(Long patientId) {
        return "/api/patients/" + patientId + "/clinical/treatments";
    }

    private static String evolution(Long patientId, long treatmentId) {
        return treatments(patientId) + "/" + treatmentId + "/evolution";
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}

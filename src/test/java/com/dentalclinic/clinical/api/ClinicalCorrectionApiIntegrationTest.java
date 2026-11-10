package com.dentalclinic.clinical.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import com.dentalclinic.clinic.application.DentistService;
import com.dentalclinic.clinic.application.DentistService.DentistData;
import com.dentalclinic.clinic.infrastructure.repository.DentistRepository;
import com.dentalclinic.clinical.infrastructure.repository.ClinicalNoteRepository;
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
 * Fase 1B.2: contrato HTTP de las correcciones clinicas append-only.
 *
 * <p>Una correccion es una fila nueva que apunta a la raiz; el original no se modifica ni se
 * elimina. Cualquier OWNER/DENTIST del grupo clinico corrige (sin exigir autoria); RECEPTIONIST y
 * PATIENT no acceden; recursos cruzados o inexistentes: 404; entradas invalidas: 400.
 */
@AutoConfigureMockMvc
class ClinicalCorrectionApiIntegrationTest extends IdentityIntegrationTestSupport {

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
    private ClinicalNoteRepository clinicalNoteRepository;
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
    void corregirNotaCreaFilaNuevaConservaOriginalYExponeMotivo() throws Exception {
        String token = dentistToken("corr-doc@c.com");
        Long patientId = newPatient("Cli", "Ente");
        long noteId = createNote(token, patientId, "Texto original.");

        TestTime.advance(java.time.Duration.ofMinutes(1));
        mockMvc.perform(post(clinical(patientId) + "/notes/" + noteId + "/corrections")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(correction("Texto corregido completo.", "Aclaracion solicitada")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("Texto corregido completo."))
                .andExpect(jsonPath("$.correctionReason").value("Aclaracion solicitada"))
                .andExpect(jsonPath("$.correctsRecordId").value(noteId))
                // La correccion hereda el contexto del original (aqui, general: sin cita).
                .andExpect(jsonPath("$.appointmentContext").doesNotExist());

        // El original no se modifica: sigue existiendo con su texto y sin motivo.
        mockMvc.perform(get(clinical(patientId) + "/notes").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].content").value("Texto original."))
                .andExpect(jsonPath("$[0].correctsRecordId").doesNotExist())
                .andExpect(jsonPath("$[1].content").value("Texto corregido completo."))
                .andExpect(jsonPath("$[1].correctsRecordId").value(noteId));

        assertThat(clinicalNoteRepository.count()).isEqualTo(2);
        assertThat(clinicalNoteRepository.findById(noteId).orElseThrow().getContent())
                .isEqualTo("Texto original.");
    }

    @Test
    void corregirUnaCorreccionApuntaSiempreALaRaiz_unSoloNivel() throws Exception {
        String token = dentistToken("corr-single@c.com");
        Long patientId = newPatient("Cli", "Ente");
        long root = createNote(token, patientId, "Raiz.");
        long c1 = correctNote(token, patientId, root, "Correccion 1.", "Motivo 1");
        long c2 = correctNote(token, patientId, c1, "Correccion 2.", "Motivo 2");

        assertThat(clinicalNoteRepository.findById(c1).orElseThrow().getCorrectsNoteId()).isEqualTo(root);
        assertThat(clinicalNoteRepository.findById(c2).orElseThrow().getCorrectsNoteId()).isEqualTo(root);
        assertThat(clinicalNoteRepository.findById(c2).orElseThrow().getCorrectsNoteId())
                .isNotEqualTo(c1);
    }

    @Test
    void corregirEvolucionCreaFilaNuevaConservaOriginal() throws Exception {
        String token = dentistToken("corr-evo@c.com");
        Long patientId = newPatient("Cli", "Ente");
        long treatmentId = createTreatment(token, patientId, "Endodoncia pieza 21");
        long evolutionId = append(token, patientId, treatmentId, "Evolucion original.");

        mockMvc.perform(post(evolution(patientId, treatmentId) + "/" + evolutionId + "/corrections")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(correction("Evolucion corregida.", "Aclaracion clinica")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("Evolucion corregida."))
                .andExpect(jsonPath("$.correctionReason").value("Aclaracion clinica"))
                .andExpect(jsonPath("$.correctsRecordId").value(evolutionId));

        mockMvc.perform(get(evolution(patientId, treatmentId)).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].content").value("Evolucion original."))
                .andExpect(jsonPath("$[1].correctsRecordId").value(evolutionId));

        assertThat(treatmentEvolutionRepository.count()).isEqualTo(2);
    }

    @Test
    void entradasInvalidas_400_yNoPersisten() throws Exception {
        String token = dentistToken("corr-invalid@c.com");
        Long patientId = newPatient("Cli", "Ente");
        long noteId = createNote(token, patientId, "Nota.");
        long treatmentId = createTreatment(token, patientId, "Limpieza");
        long evolutionId = append(token, patientId, treatmentId, "Ev.");

        for (String invalid : new String[]{
                correction("", "Motivo"),
                correction("   ", "Motivo"),
                correction("Contenido", ""),
                correction("Contenido", "   "),
                correction("Contenido", "a".repeat(1001)),
                "{}"}) {
            mockMvc.perform(post(clinical(patientId) + "/notes/" + noteId + "/corrections")
                            .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                            .content(invalid))
                    .andExpect(status().isBadRequest());
        }
        // La evolucion tambien limita el contenido a 4000 caracteres.
        mockMvc.perform(post(evolution(patientId, treatmentId) + "/" + evolutionId + "/corrections")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(correction("a".repeat(4001), "Motivo")))
                .andExpect(status().isBadRequest());

        assertThat(clinicalNoteRepository.count()).isEqualTo(1);
        assertThat(treatmentEvolutionRepository.count()).isEqualTo(1);
    }

    @Test
    void permisos_receptionistYPatient403_sinToken401() throws Exception {
        String token = dentistToken("corr-perm@c.com");
        Long patientId = newPatient("Cli", "Ente");
        long noteId = createNote(token, patientId, "Nota.");
        String body = correction("Corregida.", "Motivo");

        String receptionist = loginToken(seedUser("corr-rec@c.com", Role.RECEPTIONIST));
        mockMvc.perform(post(clinical(patientId) + "/notes/" + noteId + "/corrections")
                        .header("Authorization", bearer(receptionist)).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        User patientUser = seedUser("corr-pac@c.com", Role.PATIENT);
        Long selfPatient = patientRepository.saveAndFlush(Patient.createForUser(patientUser.getId(),
                "Pac", "Uno", null, null, null, null)).getId();
        String patient = loginToken(patientUser);
        mockMvc.perform(post(clinical(selfPatient) + "/notes/999999/corrections")
                        .header("Authorization", bearer(patient)).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(clinical(patientId) + "/notes/" + noteId + "/corrections")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        assertThat(clinicalNoteRepository.count()).isEqualTo(1);
    }

    @Test
    void recursosInexistentesOCruzados_404() throws Exception {
        String token = dentistToken("corr-404@c.com");
        Long patientA = newPatient("Ana", "Propia");
        Long patientB = newPatient("Beto", "Ajena");
        long noteA = createNote(token, patientA, "Nota de Ana.");
        long treatmentA = createTreatment(token, patientA, "Tratamiento de Ana");
        long evolutionA = append(token, patientA, treatmentA, "Ev de Ana.");
        String body = correction("Corregida.", "Motivo");

        // Nota inexistente.
        mockMvc.perform(post(clinical(patientA) + "/notes/999999/corrections")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
        // Nota de otro paciente (tratada como inexistente).
        mockMvc.perform(post(clinical(patientB) + "/notes/" + noteA + "/corrections")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
        // Evolucion inexistente.
        mockMvc.perform(post(evolution(patientA, treatmentA) + "/999999/corrections")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
        // Evolucion de otro tratamiento/paciente.
        mockMvc.perform(post(evolution(patientB, treatmentA) + "/" + evolutionA + "/corrections")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());

        assertThat(clinicalNoteRepository.count()).isEqualTo(1);
        assertThat(treatmentEvolutionRepository.count()).isEqualTo(1);
    }

    private long createNote(String token, Long patientId, String content) throws Exception {
        MvcResult created = mockMvc.perform(post(clinical(patientId) + "/notes")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":%s}
                                """.formatted(jsonString(content))))
                .andExpect(status().isCreated()).andReturn();
        return id(created);
    }

    private long correctNote(String token, Long patientId, long noteId, String content, String reason)
            throws Exception {
        MvcResult created = mockMvc.perform(post(clinical(patientId) + "/notes/" + noteId + "/corrections")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(correction(content, reason)))
                .andExpect(status().isCreated()).andReturn();
        return id(created);
    }

    private long createTreatment(String token, Long patientId, String description) throws Exception {
        MvcResult created = mockMvc.perform(post(clinical(patientId) + "/treatments")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":%s}
                                """.formatted(jsonString(description))))
                .andExpect(status().isCreated()).andReturn();
        return id(created);
    }

    private long append(String token, Long patientId, long treatmentId, String content) throws Exception {
        MvcResult created = mockMvc.perform(post(evolution(patientId, treatmentId))
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":%s}
                                """.formatted(jsonString(content))))
                .andExpect(status().isCreated()).andReturn();
        return id(created);
    }

    private long id(MvcResult result) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private static String correction(String content, String reason) {
        return "{\"content\":%s,\"correctionReason\":%s}".formatted(jsonString(content), jsonString(reason));
    }

    private static String jsonString(String value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
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

    private String loginToken(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(user.getEmail(), PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private User seedUser(String email, Role role) {
        return userRepository.findByEmail(email)
                .orElseGet(() -> userRepository.saveAndFlush(
                        User.create(email, passwordEncoder.encode(PASSWORD), role)));
    }

    private static String clinical(Long patientId) {
        return "/api/patients/" + patientId + "/clinical";
    }

    private static String evolution(Long patientId, long treatmentId) {
        return clinical(patientId) + "/treatments/" + treatmentId + "/evolution";
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}

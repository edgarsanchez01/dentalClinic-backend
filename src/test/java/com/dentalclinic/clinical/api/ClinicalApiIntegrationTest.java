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
 * Contrato HTTP del expediente clinico (matriz §7.4, grupo {@code /api/patients/{id}/clinical}).
 *
 * <p>DENTIST lee y escribe; OWNER solo lee; RECEPTIONIST y PATIENT no tienen acceso. Las notas son
 * inmutables (RF-CX-04): no existen rutas de edicion ni borrado.
 */
@AutoConfigureMockMvc
class ClinicalApiIntegrationTest extends IdentityIntegrationTestSupport {

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
    void dentist_guardaExpedienteYAgregaNotas() throws Exception {
        String token = dentistToken("doc@c.com");
        Long patientId = patientRepository.saveAndFlush(Patient.create("Cli", "Ente", null, null,
                null, null)).getId();

        mockMvc.perform(put(clinical(patientId)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"medicalBackground":"Hipertension","allergies":"Penicilina",
                                 "clinicalHistory":"Sin cirugias","generalObservations":"Buen estado"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allergies").value("Penicilina"));

        mockMvc.perform(get(clinical(patientId)).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.medicalBackground").value("Hipertension"))
                .andExpect(jsonPath("$.clinicalHistory").value("Sin cirugias"));

        mockMvc.perform(post(clinical(patientId) + "/notes").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Primera revision sin hallazgos."}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("Primera revision sin hallazgos."))
                .andExpect(jsonPath("$.authorName").value("Dora Dentista"))
                .andExpect(jsonPath("$.authorDentistId").isNumber());

        mockMvc.perform(get(clinical(patientId) + "/notes").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        assertThat(clinicalNoteRepository.count()).isEqualTo(1);
    }

    @Test
    void owner_leePeroNoEscribe() throws Exception {
        String token = loginToken(seedUser("own@c.com", Role.OWNER));
        Long patientId = patientRepository.saveAndFlush(Patient.create("Cli", "Ente", null, null,
                null, null)).getId();

        mockMvc.perform(get(clinical(patientId)).header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(put(clinical(patientId)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"allergies":"X"}
                                """))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(clinical(patientId) + "/notes").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"No deberia"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void receptionist_sinAcceso_403() throws Exception {
        String token = loginToken(seedUser("rec@c.com", Role.RECEPTIONIST));
        Long patientId = patientRepository.saveAndFlush(Patient.create("Cli", "Ente", null, null,
                null, null)).getId();
        mockMvc.perform(get(clinical(patientId)).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void patient_sinAcceso_403inclusoASuPropioExpediente() throws Exception {
        User patientUser = seedUser("pac@c.com", Role.PATIENT);
        Long patientId = patientRepository.saveAndFlush(Patient.createForUser(patientUser.getId(),
                "Pac", "Uno", null, null, null, null)).getId();
        String token = loginToken(patientUser);
        mockMvc.perform(get(clinical(patientId)).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void lasNotasSonInmutables_noExistenRutasDeEdicionNiBorrado() throws Exception {
        String token = dentistToken("doc2@c.com");
        Long patientId = patientRepository.saveAndFlush(Patient.create("Cli", "Ente", null, null,
                null, null)).getId();
        MvcResult note = mockMvc.perform(post(clinical(patientId) + "/notes").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Nota original."}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long noteId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(note.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(put(clinical(patientId) + "/notes/" + noteId).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"Editada"}
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(clinical(patientId) + "/notes/" + noteId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        assertThat(clinicalNoteRepository.count()).isEqualTo(1);
    }

    /**
     * La colección de notas solo admite GET/POST. Un verbo de mutación debe responder 405 (método
     * no permitido), no 500: no existe ruta de edición ni borrado (RF-CX-04).
     */
    @Test
    void metodosNoSoportadosEnLaColeccionDeNotas_405() throws Exception {
        String token = dentistToken("doc4@c.com");
        Long patientId = patientRepository.saveAndFlush(Patient.create("Cli", "Ente", null, null,
                null, null)).getId();

        mockMvc.perform(put(clinical(patientId) + "/notes").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"No editable."}
                                """))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
        mockMvc.perform(patch(clinical(patientId) + "/notes").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"No editable."}
                                """))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
        mockMvc.perform(delete(clinical(patientId) + "/notes").header("Authorization", bearer(token)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405));
        assertThat(clinicalNoteRepository.count()).isZero();
    }

    @Test
    void pacienteInexistente_404() throws Exception {
        String token = dentistToken("doc3@c.com");
        mockMvc.perform(get(clinical(999999L)).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
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

    private static String clinical(Long patientId) {
        return "/api/patients/" + patientId + "/clinical";
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
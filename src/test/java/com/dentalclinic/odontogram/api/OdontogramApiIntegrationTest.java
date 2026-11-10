package com.dentalclinic.odontogram.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

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
import com.dentalclinic.clinical.domain.Treatment;
import com.dentalclinic.clinical.domain.TreatmentStatus;
import com.dentalclinic.clinical.infrastructure.repository.TreatmentRepository;
import com.dentalclinic.identity.IdentityIntegrationTestSupport;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.infrastructure.repository.InvitationRepository;
import com.dentalclinic.identity.infrastructure.repository.PasswordResetTokenRepository;
import com.dentalclinic.identity.infrastructure.repository.RefreshSessionRepository;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;
import com.dentalclinic.odontogram.infrastructure.repository.ToothHistoryRepository;
import com.dentalclinic.odontogram.infrastructure.repository.ToothRecordRepository;
import com.dentalclinic.odontogram.infrastructure.repository.TreatmentToothRepository;
import com.dentalclinic.patient.domain.Patient;
import com.dentalclinic.patient.infrastructure.repository.PatientRepository;

/**
 * Contrato HTTP del odontograma (matriz 7.4, grupo {@code /api/patients/{id}/odontogram}).
 *
 * <p>OWNER y DENTIST leen y escriben (el OWNER siempre tiene perfil de dentista, AD-11);
 * RECEPTIONIST no accede y el PATIENT solo ve el resumen de su propio odontograma
 * (RF-OD-01/03/04/05/08). El historial es append-only y los cambios de condicion dejan rastro de
 * quien y cuando.
 */
@AutoConfigureMockMvc
class OdontogramApiIntegrationTest extends IdentityIntegrationTestSupport {

    private static final String PASSWORD = "ClaveOdonto-2026";

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
    private TreatmentRepository treatmentRepository;
    @Autowired
    private ToothRecordRepository toothRecordRepository;
    @Autowired
    private ToothHistoryRepository toothHistoryRepository;
    @Autowired
    private TreatmentToothRepository treatmentToothRepository;
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
    void dentist_creaPiezaYLaActualizaConHistorialAppendOnly() throws Exception {
        String token = dentistToken("odo@c.com");
        Long patientId = patient("Cli", "Ente");

        mockMvc.perform(post(odontogram(patientId)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toothFdi":21,"condition":"CARIES","notes":"Caries oclusal"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.toothFdi").value(21))
                .andExpect(jsonPath("$.dentition").value("PERMANENT"))
                .andExpect(jsonPath("$.condition").value("CARIES"))
                .andExpect(jsonPath("$.notes").value("Caries oclusal"))
                .andExpect(jsonPath("$.history", hasSize(1)))
                .andExpect(jsonPath("$.history[0].newCondition").value("CARIES"))
                .andExpect(jsonPath("$.history[0].previousCondition").doesNotExist())
                .andExpect(jsonPath("$.history[0].actorName").value("Dora Dentista"));

        mockMvc.perform(get(odontogram(patientId)).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].toothFdi").value(21));

        mockMvc.perform(put(odontogram(patientId) + "/21").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"condition":"RESTORED","notes":"Obturada"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.condition").value("RESTORED"))
                .andExpect(jsonPath("$.history", hasSize(2)))
                .andExpect(jsonPath("$.history[1].previousCondition").value("CARIES"))
                .andExpect(jsonPath("$.history[1].newCondition").value("RESTORED"));

        assertThat(toothRecordRepository.count()).isEqualTo(1);
        assertThat(toothHistoryRepository.count()).isEqualTo(2);
    }

    @Test
    void aceptaPiezasPermanentesYYTemporales_peroRechazaFdiInvalido() throws Exception {
        String token = dentistToken("odo2@c.com");
        Long patientId = patient("Cli", "Dos");

        mockMvc.perform(post(odontogram(patientId)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toothFdi":85,"condition":"SEALED"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dentition").value("TEMPORARY"));

        for (int invalid : new int[] {19, 29, 49, 56, 90, 0}) {
            mockMvc.perform(post(odontogram(patientId)).header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"toothFdi":%d,"condition":"CARIES"}
                                    """.formatted(invalid)))
                    .andExpect(status().isBadRequest());
        }

        // Condición no reconocida en el JSON -> 400.
        mockMvc.perform(post(odontogram(patientId)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toothFdi":11,"condition":"BROKEN"}
                                """))
                .andExpect(status().isBadRequest());

        // Pieza ya registrada -> 409.
        mockMvc.perform(post(odontogram(patientId)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toothFdi":85,"condition":"HEALTHY"}
                                """))
                .andExpect(status().isConflict());

        assertThat(toothRecordRepository.count()).isEqualTo(1);
    }

    @Test
    void vinculaTratamientoDelPaciente_yRechazaTratamientoAjeno() throws Exception {
        String token = dentistToken("odo3@c.com");
        Long patientId = patient("Cli", "Tres");
        Long otherPatientId = patient("Otro", "Paciente");

        Treatment own = treatmentRepository.saveAndFlush(Treatment.create(patientId, null,
                "Obturación pieza 36", null, TreatmentStatus.PLANNED, null, null, Instant.now()));
        Treatment foreign = treatmentRepository.saveAndFlush(Treatment.create(otherPatientId, null,
                "Ajeno", null, TreatmentStatus.PLANNED, null, null, Instant.now()));

        mockMvc.perform(post(odontogram(patientId)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toothFdi":36,"condition":"CARIES","treatmentIds":[%d]}
                                """.formatted(own.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.treatmentIds", hasSize(1)))
                .andExpect(jsonPath("$.treatmentIds[0]").value(own.getId().intValue()));
        assertThat(treatmentToothRepository.count()).isEqualTo(1);

        mockMvc.perform(post(odontogram(patientId)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toothFdi":37,"condition":"CARIES","treatmentIds":[%d]}
                                """.formatted(foreign.getId())))
                .andExpect(status().isNotFound());

        assertThat(treatmentToothRepository.count()).isEqualTo(1);
        assertThat(toothRecordRepository.findByPatientIdAndToothFdi(patientId, 37)).isEmpty();
    }

    @Test
    void owner_leeYEsEscribeComoPersonalClinico() throws Exception {
        String token = ownerToken("own@c.com");
        Long patientId = patient("Cli", "Cuatro");

        mockMvc.perform(get(odontogram(patientId)).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(post(odontogram(patientId)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toothFdi":11,"condition":"CARIES","notes":"Registra el OWNER"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.condition").value("CARIES"))
                .andExpect(jsonPath("$.history", hasSize(1)))
                .andExpect(jsonPath("$.history[0].newCondition").value("CARIES"))
                .andExpect(jsonPath("$.history[0].actorName").value("Olivia Owner"));
        mockMvc.perform(put(odontogram(patientId) + "/11").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"condition":"RESTORED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.condition").value("RESTORED"))
                .andExpect(jsonPath("$.history", hasSize(2)))
                .andExpect(jsonPath("$.history[1].previousCondition").value("CARIES"))
                .andExpect(jsonPath("$.history[1].newCondition").value("RESTORED"))
                .andExpect(jsonPath("$.history[1].actorName").value("Olivia Owner"));

        assertThat(toothRecordRepository.count()).isEqualTo(1);
        assertThat(toothHistoryRepository.count()).isEqualTo(2);
    }

    @Test
    void receptionist_sinAcceso_403() throws Exception {
        String token = loginToken(seedUser("rec@c.com", Role.RECEPTIONIST));
        Long patientId = patient("Cli", "Cinco");
        mockMvc.perform(get(odontogram(patientId)).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(odontogram(patientId)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toothFdi":11,"condition":"CARIES"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void patient_soloVeSuResumen_yNoAccedeAlAjeno() throws Exception {
        String dentistToken = dentistToken("odo4@c.com");
        User patientUser = seedUser("pac@c.com", Role.PATIENT);
        Long patientId = patientRepository.saveAndFlush(Patient.createForUser(patientUser.getId(),
                "Pac", "Uno", null, null, null, null)).getId();
        Long otherPatientId = patient("Ajeno", "Paciente");

        mockMvc.perform(post(odontogram(patientId)).header("Authorization", bearer(dentistToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toothFdi":46,"condition":"EXTRACTED","notes":"No debe verse"}
                                """))
                .andExpect(status().isCreated());

        String token = loginToken(patientUser);
        mockMvc.perform(get(odontogram(patientId)).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].toothFdi").value(46))
                .andExpect(jsonPath("$[0].condition").value("EXTRACTED"))
                .andExpect(jsonPath("$[0].dentition").value("PERMANENT"))
                .andExpect(jsonPath("$[0].notes").doesNotExist())
                .andExpect(jsonPath("$[0].history").doesNotExist())
                .andExpect(jsonPath("$[0].patientId").doesNotExist());

        mockMvc.perform(get(odontogram(otherPatientId)).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(odontogram(patientId)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toothFdi":47,"condition":"CARIES"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void pacienteInexistenteYPiezaNoRegistrada_noExisten_404() throws Exception {
        String token = dentistToken("odo5@c.com");
        Long patientId = patient("Cli", "Seis");

        mockMvc.perform(get(odontogram(999999L)).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(odontogram(patientId) + "/11").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(odontogram(999999L) + "/11").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }

    private Long patient(String firstName, String lastName) {
        return patientRepository.saveAndFlush(Patient.create(firstName, lastName, null, null,
                null, null)).getId();
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

    private static String odontogram(Long patientId) {
        return "/api/patients/" + patientId + "/odontogram";
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}

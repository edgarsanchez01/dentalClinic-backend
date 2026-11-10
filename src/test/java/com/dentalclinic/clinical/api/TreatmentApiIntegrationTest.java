package com.dentalclinic.clinical.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.dentalclinic.clinical.infrastructure.repository.TreatmentRepository;
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
 * Contrato HTTP del registro de tratamientos (matriz 7.4, grupo
 * {@code /api/patients/{id}/clinical}).
 *
 * <p>OWNER y DENTIST leen y escriben (el OWNER siempre tiene perfil de dentista, AD-11);
 * RECEPTIONIST y PATIENT no tienen acceso (RF-TR-04/06, P-05). El catalogo administrativo de
 * servicios es OTRO modulo: aqui solo hay tratamiento clinico.
 */
@AutoConfigureMockMvc
class TreatmentApiIntegrationTest extends IdentityIntegrationTestSupport {

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
    private TreatmentRepository treatmentRepository;
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
    void dentist_creaListaYActualizaTratamiento() throws Exception {
        String token = dentistToken("doc@c.com");
        Long patientId = patientRepository.saveAndFlush(Patient.create("Cli", "Ente", null, null,
                null, null)).getId();

        mockMvc.perform(post(treatments(patientId)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Endodoncia pieza 21","diagnosis":"Pulpitis",
                                 "status":"PLANNED","notes":"Cita previa de valoracion","price":120.50}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").value("Endodoncia pieza 21"))
                .andExpect(jsonPath("$.diagnosis").value("Pulpitis"))
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.price").value(120.5))
                .andExpect(jsonPath("$.patientId").value(patientId.intValue()));

        MvcResult created = mockMvc.perform(get(treatments(patientId))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andReturn();
        long treatmentId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(created.getResponse().getContentAsString()).get(0).get("id").asLong();

        mockMvc.perform(put(treatments(patientId) + "/" + treatmentId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Endodoncia pieza 21","diagnosis":"Pulpitis",
                                 "status":"IN_PROGRESS","notes":"Segunda sesion","price":120.50}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.notes").value("Segunda sesion"));

        mockMvc.perform(get(treatments(patientId) + "/" + treatmentId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        assertThat(treatmentRepository.count()).isEqualTo(1);
    }

    @Test
    void traitementSinEstado_nacePlanned() throws Exception {
        String token = dentistToken("doc2@c.com");
        Long patientId = patientRepository.saveAndFlush(Patient.create("Cli", "Ente", null, null,
                null, null)).getId();

        mockMvc.perform(post(treatments(patientId)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Limpieza dental"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PLANNED"));
    }

    @Test
    void citaInexistente_404_yEstadoInvalido_oDescripcionEnBlanco_400() throws Exception {
        String token = dentistToken("doc3@c.com");
        Long patientId = patientRepository.saveAndFlush(Patient.create("Cli", "Ente", null, null,
                null, null)).getId();

        mockMvc.perform(post(treatments(patientId)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Extraccion","appointmentId":999999}
                                """))
                .andExpect(status().isNotFound());

        mockMvc.perform(post(treatments(patientId)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Extraccion","status":"ENTREGADO"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(treatments(patientId)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"   ","price":-5}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(treatmentRepository.count()).isZero();
    }

    @Test
    void owner_leeYEsEscribeComoPersonalClinico() throws Exception {
        String token = ownerToken("own@c.com");
        Long patientId = patientRepository.saveAndFlush(Patient.create("Cli", "Ente", null, null,
                null, null)).getId();

        mockMvc.perform(get(treatments(patientId)).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        MvcResult created = mockMvc.perform(post(treatments(patientId)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Endodoncia pieza 21","status":"PLANNED","price":120.50}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").value("Endodoncia pieza 21"))
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andReturn();
        long treatmentId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(created.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(put(treatments(patientId) + "/" + treatmentId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Endodoncia pieza 21","status":"IN_PROGRESS"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        assertThat(treatmentRepository.count()).isEqualTo(1);
    }

    @Test
    void receptionist_sinAcceso_403() throws Exception {
        String token = loginToken(seedUser("rec@c.com", Role.RECEPTIONIST));
        Long patientId = patientRepository.saveAndFlush(Patient.create("Cli", "Ente", null, null,
                null, null)).getId();
        mockMvc.perform(get(treatments(patientId)).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(treatments(patientId)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"No"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void patient_sinAcceso_403inclusoASuPropioExpediente() throws Exception {
        User patientUser = seedUser("pac@c.com", Role.PATIENT);
        Long patientId = patientRepository.saveAndFlush(Patient.createForUser(patientUser.getId(),
                "Pac", "Uno", null, null, null, null)).getId();
        String token = loginToken(patientUser);
        mockMvc.perform(get(treatments(patientId)).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    void pacienteInexistenteYTratamientoAjeno_noExisten_404() throws Exception {
        String token = dentistToken("doc4@c.com");
        Long patientId = patientRepository.saveAndFlush(Patient.create("Cli", "Ente", null, null,
                null, null)).getId();

        mockMvc.perform(get(treatments(999999L)).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(treatments(patientId) + "/999999").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(treatments(999999L) + "/1").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }

    @Test
    void tratamientoSinPiezas_exponeToothFdisVacio() throws Exception {
        String token = dentistToken("doc5@c.com");
        Long patientId = patientRepository.saveAndFlush(Patient.create("Cli", "Ente", null, null,
                null, null)).getId();

        long treatmentId = createTreatment(token, patientId, "Limpieza dental");

        mockMvc.perform(get(treatments(patientId) + "/" + treatmentId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toothFdis", hasSize(0)));

        mockMvc.perform(get(treatments(patientId)).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].toothFdis", hasSize(0)));
    }

    @Test
    void tratamientoConUnaPieza_exponeSuFdi() throws Exception {
        String token = dentistToken("doc6@c.com");
        Long patientId = patientRepository.saveAndFlush(Patient.create("Cli", "Ente", null, null,
                null, null)).getId();
        long treatmentId = createTreatment(token, patientId, "Obturacion pieza 36");

        linkTooth(token, patientId, 36, treatmentId);

        mockMvc.perform(get(treatments(patientId) + "/" + treatmentId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toothFdis", hasSize(1)))
                .andExpect(jsonPath("$.toothFdis[0]").value(36));

        mockMvc.perform(get(treatments(patientId)).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].toothFdis", hasSize(1)))
                .andExpect(jsonPath("$[0].toothFdis[0]").value(36));
    }

    @Test
    void tratamientoConVariasPiezas_exponeFdisOrdenados() throws Exception {
        String token = dentistToken("doc7@c.com");
        Long patientId = patientRepository.saveAndFlush(Patient.create("Cli", "Ente", null, null,
                null, null)).getId();
        long treatmentId = createTreatment(token, patientId, "Ortodoncia de sector");

        // Se registran en orden inverso para comprobar que la respuesta se ordena de forma ascendente.
        linkTooth(token, patientId, 36, treatmentId);
        linkTooth(token, patientId, 11, treatmentId);

        mockMvc.perform(get(treatments(patientId)).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].toothFdis", hasSize(2)))
                .andExpect(jsonPath("$[0].toothFdis[0]").value(11))
                .andExpect(jsonPath("$[0].toothFdis[1]").value(36));
    }

    @Test
    void desvinculacionDesdeOdontograma_seReflejaEnElTratamiento() throws Exception {
        String token = dentistToken("doc8@c.com");
        Long patientId = patientRepository.saveAndFlush(Patient.create("Cli", "Ente", null, null,
                null, null)).getId();
        long treatmentId = createTreatment(token, patientId, "Endodoncia pieza 36");

        linkTooth(token, patientId, 36, treatmentId);
        mockMvc.perform(get(treatments(patientId) + "/" + treatmentId)
                        .header("Authorization", bearer(token)))
                .andExpect(jsonPath("$.toothFdis[0]").value(36));

        mockMvc.perform(put("/api/patients/" + patientId + "/odontogram/36")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"condition":"RESTORED"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get(treatments(patientId) + "/" + treatmentId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toothFdis", hasSize(0)));
    }

    @Test
    void noExponePiezasDeOtroPaciente() throws Exception {
        String token = dentistToken("doc9@c.com");
        Long patientId = patientRepository.saveAndFlush(Patient.create("Cli", "Ente", null, null,
                null, null)).getId();
        Long otherPatientId = patientRepository.saveAndFlush(Patient.create("Otro", "Paciente", null,
                null, null, null)).getId();

        long ownTreatment = createTreatment(token, patientId, "Propio");
        long otherTreatment = createTreatment(token, otherPatientId, "Ajeno");
        linkTooth(token, patientId, 11, ownTreatment);
        linkTooth(token, otherPatientId, 46, otherTreatment);

        mockMvc.perform(get(treatments(patientId)).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value((int) ownTreatment))
                .andExpect(jsonPath("$[0].toothFdis", hasSize(1)))
                .andExpect(jsonPath("$[0].toothFdis[0]").value(11));
    }

    private long createTreatment(String token, Long patientId, String description) throws Exception {
        MvcResult created = mockMvc.perform(post(treatments(patientId))
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"%s"}
                                """.formatted(description)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.toothFdis", hasSize(0)))
                .andReturn();
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(created.getResponse().getContentAsString()).get("id").asLong();
    }

    private void linkTooth(String token, Long patientId, int toothFdi, long treatmentId)
            throws Exception {
        mockMvc.perform(post("/api/patients/" + patientId + "/odontogram")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toothFdi":%d,"condition":"CARIES","treatmentIds":[%d]}
                                """.formatted(toothFdi, treatmentId)))
                .andExpect(status().isCreated());
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

    private static String treatments(Long patientId) {
        return "/api/patients/" + patientId + "/clinical/treatments";
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
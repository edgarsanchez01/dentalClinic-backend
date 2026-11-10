package com.dentalclinic.odontogram.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
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
import com.dentalclinic.identity.IdentityIntegrationTestSupport;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.infrastructure.repository.InvitationRepository;
import com.dentalclinic.identity.infrastructure.repository.PasswordResetTokenRepository;
import com.dentalclinic.identity.infrastructure.repository.RefreshSessionRepository;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;
import com.dentalclinic.odontogram.infrastructure.repository.ToothSurfaceHistoryRepository;
import com.dentalclinic.odontogram.infrastructure.repository.ToothSurfaceRecordRepository;
import com.dentalclinic.patient.domain.Patient;
import com.dentalclinic.patient.infrastructure.repository.PatientRepository;

/**
 * Contrato HTTP de las superficies del odontograma (RF-OD-02/03/04/08, F7.4.3.2).
 *
 * <p>Las superficies se registran por pieza y por grupo de diente (anteriores INCISAL,
 * posteriores OCCLUSAL); cada cambio de condición deja historial append-only con autor. Los
 * permisos reutilizan el grupo {@code /api/patients/{id}/odontogram/**}: OWNER y DENTIST
 * leen/escriben (el OWNER siempre tiene perfil de dentista, AD-11), RECEPTIONIST 403 y PATIENT
 * solo el resumen de su propio odontograma.
 */
@AutoConfigureMockMvc
class ToothSurfaceApiIntegrationTest extends IdentityIntegrationTestSupport {

    private static final String PASSWORD = "ClaveSuperficie-2026";

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
    private ToothSurfaceRecordRepository toothSurfaceRecordRepository;
    @Autowired
    private ToothSurfaceHistoryRepository toothSurfaceHistoryRepository;
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
    void dentista_creaYActualizaSuperficie_conHistorialAppendOnly() throws Exception {
        String token = dentistToken("sup0@c.com");
        Long patientId = patient("Cli", "Superficie");

        int tooth = 16;
        mockMvc.perform(post(odontogram(patientId)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toothFdi":%d,"condition":"HEALTHY"}
                                """.formatted(tooth)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.surfaces", hasSize(0)));

        mockMvc.perform(post(surfaces(patientId, tooth)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"surface":"MESIAL","condition":"CARIES","notes":"Caries mesial"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.surface").value("MESIAL"))
                .andExpect(jsonPath("$.condition").value("CARIES"))
                .andExpect(jsonPath("$.notes").value("Caries mesial"))
                .andExpect(jsonPath("$.history", hasSize(1)))
                .andExpect(jsonPath("$.history[0].previousCondition").doesNotExist())
                .andExpect(jsonPath("$.history[0].newCondition").value("CARIES"))
                .andExpect(jsonPath("$.history[0].actorName").value("Dora Dentista"));

        // La pieza ahora expone su superficie.
        mockMvc.perform(get(odontogram(patientId) + "/" + tooth).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.surfaces", hasSize(1)))
                .andExpect(jsonPath("$.surfaces[0].surface").value("MESIAL"))
                .andExpect(jsonPath("$.surfaces[0].condition").value("CARIES"));

        // Actualización: la transición queda registrada.
        mockMvc.perform(put(surface(patientId, tooth, "MESIAL")).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"condition":"RESTORED","notes":"Obturada"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.condition").value("RESTORED"))
                .andExpect(jsonPath("$.history", hasSize(2)))
                .andExpect(jsonPath("$.history[1].previousCondition").value("CARIES"))
                .andExpect(jsonPath("$.history[1].newCondition").value("RESTORED"));

        // Reescribir la misma condición no añade historial: nada se sobrescribe en silencio.
        mockMvc.perform(put(surface(patientId, tooth, "MESIAL")).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"condition":"RESTORED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history", hasSize(2)));

        assertThat(toothSurfaceRecordRepository.count()).isEqualTo(1);
        assertThat(toothSurfaceHistoryRepository.count()).isEqualTo(2);
    }

    @Test
    void validaSuperficiesPorGrupoDeDiente() throws Exception {
        String token = dentistToken("sup1@c.com");
        Long patientId = patient("Cli", "Reglas");

        createTooth(token, patientId, 11); // anterior
        createTooth(token, patientId, 16); // posterior

        // Oclusal en anterior -> 400.
        mockMvc.perform(post(surfaces(patientId, 11)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"surface":"OCCLUSAL","condition":"CARIES"}
                                """))
                .andExpect(status().isBadRequest());
        // Incisal en posterior -> 400.
        mockMvc.perform(post(surfaces(patientId, 16)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"surface":"INCISAL","condition":"CARIES"}
                                """))
                .andExpect(status().isBadRequest());

        // Temporales siguen la misma regla por posición: 51 anterior acepta INCISAL.
        createTooth(token, patientId, 51);
        mockMvc.perform(post(surfaces(patientId, 51)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"surface":"INCISAL","condition":"SEALED"}
                                """))
                .andExpect(status().isCreated());

        // Superficie inexistente en el cuerpo del JSON -> 400.
        mockMvc.perform(post(surfaces(patientId, 16)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"surface":"FUGA","condition":"CARIES"}
                                """))
                .andExpect(status().isBadRequest());
        // Superficie inexistente en la ruta -> 400.
        mockMvc.perform(get(surface(patientId, 16, "FUGA")).header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());

        assertThat(toothSurfaceRecordRepository.count()).isEqualTo(1);
    }

    @Test
    void superficieDuplicada_409_ySuperficieAjeno_oInexistente_404() throws Exception {
        String token = dentistToken("sup2@c.com");
        Long patientId = patient("Cli", "Conflicto");
        Long otherPatientId = patient("Otro", "Paciente");

        createTooth(token, patientId, 26);
        createTooth(token, otherPatientId, 27);

        mockMvc.perform(post(surfaces(patientId, 26)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"surface":"DISTAL","condition":"CARIES"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post(surfaces(patientId, 26)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"surface":"DISTAL","condition":"FRACTURED"}
                                """))
                .andExpect(status().isConflict());

        // Superficie inexistente en una pieza existente -> 404.
        mockMvc.perform(get(surface(patientId, 26, "VESTIBULAR")).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        // Pieza inexistente -> 404.
        mockMvc.perform(get(surfaces(patientId, 99)).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        // Superficie de una pieza de otro paciente -> 404 (indistinguible de inexistente).
        mockMvc.perform(post(surfaces(otherPatientId, 27)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"surface":"DISTAL","condition":"CARIES"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get(surfaces(patientId, 27)).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());

        assertThat(toothSurfaceRecordRepository.count()).isEqualTo(2);
    }

    @Test
    void owner_leeYEsEscribeSuperficiesConAutor() throws Exception {
        String token = ownerToken("supown@c.com");
        Long patientId = patient("Cli", "Owner");
        createTooth(token, patientId, 46);

        mockMvc.perform(post(surfaces(patientId, 46)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"surface":"OCCLUSAL","condition":"CARIES"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.surface").value("OCCLUSAL"))
                .andExpect(jsonPath("$.history", hasSize(1)))
                .andExpect(jsonPath("$.history[0].newCondition").value("CARIES"))
                .andExpect(jsonPath("$.history[0].actorName").value("Olivia Owner"));

        mockMvc.perform(get(surfaces(patientId, 46)).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].surface").value("OCCLUSAL"))
                .andExpect(jsonPath("$[0].history", hasSize(1)));

        mockMvc.perform(put(surface(patientId, 46, "OCCLUSAL")).header("Authorization", bearer(token))
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
    }

    @Test
    void receptionist_sinAcceso_403() throws Exception {
        String token = loginToken(seedUser("suprec@c.com", Role.RECEPTIONIST));
        Long patientId = patient("Cli", "Recep");
        mockMvc.perform(get(surfaces(patientId, 11)).header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(surfaces(patientId, 11)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"surface":"MESIAL","condition":"CARIES"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void patient_soloSuResumenDeSuperficieSinDetalleYNoAjeno() throws Exception {
        String dentistToken = dentistToken("sup4@c.com");
        User patientUser = seedUser("supac@c.com", Role.PATIENT);
        Long patientId = patientRepository.saveAndFlush(Patient.createForUser(patientUser.getId(),
                "Pac", "Dos", null, null, null, null)).getId();
        Long otherPatientId = patient("Ajeno", "Paciente");

        createTooth(dentistToken, patientId, 36);
        mockMvc.perform(post(surfaces(patientId, 36)).header("Authorization", bearer(dentistToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"surface":"OCCLUSAL","condition":"CARIES","notes":"No debe verse"}
                                """))
                .andExpect(status().isCreated());

        String token = loginToken(patientUser);
        mockMvc.perform(get(surfaces(patientId, 36)).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].surface").value("OCCLUSAL"))
                .andExpect(jsonPath("$[0].condition").value("CARIES"))
                .andExpect(jsonPath("$[0].notes").doesNotExist())
                .andExpect(jsonPath("$[0].history").doesNotExist());

        // El resumen de pieza no filtra el detalle de superficies.
        mockMvc.perform(get(odontogram(patientId) + "/36").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toothFdi").value(36))
                .andExpect(jsonPath("$.surfaces").doesNotExist());

        mockMvc.perform(get(surfaces(otherPatientId, 36)).header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(surfaces(patientId, 36)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"surface":"DISTAL","condition":"CARIES"}
                                """))
                .andExpect(status().isForbidden());
    }

    private void createTooth(String token, Long patientId, int toothFdi) throws Exception {
        mockMvc.perform(post(odontogram(patientId)).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toothFdi":%d,"condition":"HEALTHY"}
                                """.formatted(toothFdi)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.toothFdi", is(toothFdi)));
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

    private static String surfaces(Long patientId, int toothFdi) {
        return odontogram(patientId) + "/" + toothFdi + "/surfaces";
    }

    private static String surface(Long patientId, int toothFdi, String surface) {
        return surfaces(patientId, toothFdi) + "/" + surface;
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
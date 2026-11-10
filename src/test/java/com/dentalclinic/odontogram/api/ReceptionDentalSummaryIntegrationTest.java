package com.dentalclinic.odontogram.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import com.dentalclinic.identity.IdentityIntegrationTestSupport;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;
import com.dentalclinic.odontogram.domain.*;
import com.dentalclinic.odontogram.infrastructure.repository.*;
import com.dentalclinic.patient.domain.Patient;
import com.dentalclinic.patient.infrastructure.repository.PatientRepository;
import com.dentalclinic.clinical.domain.Treatment;
import com.dentalclinic.clinical.domain.TreatmentStatus;
import com.dentalclinic.clinical.infrastructure.repository.TreatmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Explicitly authorized Testcontainers-only run; inherited guard rejects operator datasources. */
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:tc:postgresql:16:///dentalclinic_ux05f?TC_TMPFS=/var/lib/postgresql/data:rw")
class ReceptionDentalSummaryIntegrationTest extends IdentityIntegrationTestSupport {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PatientRepository patients;
    @Autowired ToothRecordRepository teeth;
    @Autowired ToothSurfaceRecordRepository surfaces;
    @Autowired TreatmentRepository treatments;
    @Autowired TreatmentToothRepository links;
    @Autowired PasswordEncoder passwords;
    final ObjectMapper mapper = new ObjectMapper();
    static final String PASSWORD = "Synthetic-Ux05F-2026";
    Long patientId, otherId;
    String receptionToken, patientToken;

    @BeforeEach void seed() throws Exception {
        User receptionist = user(Role.RECEPTIONIST);
        User patient = user(Role.PATIENT);
        receptionToken = login(receptionist);
        patientToken = login(patient);
        patientId = patients.saveAndFlush(Patient.createForUser(patient.getId(), "Paciente", "Sintético", null, null, null, null)).getId();
        otherId = patients.saveAndFlush(Patient.create("Otro", "Sintético", null, null, null, null)).getId();
        Instant now = Instant.parse("2026-10-24T10:00:00Z");
        var tooth = teeth.saveAndFlush(ToothRecord.create(patientId, 16, ToothCondition.CARIES, "PRIVATE_TOOTH_NOTES", now));
        surfaces.saveAndFlush(ToothSurfaceRecord.create(tooth.getId(), 16, ToothSurface.MESIAL, ToothCondition.RESTORED, "PRIVATE_SURFACE_NOTES", now));
        teeth.saveAndFlush(ToothRecord.create(patientId, 55, ToothCondition.HEALTHY, "PRIVATE_TEMPORARY_NOTES", now));
        teeth.saveAndFlush(ToothRecord.create(otherId, 48, ToothCondition.EXTRACTED, "OTHER_PATIENT_PRIVATE", now));
        var treatment = treatments.saveAndFlush(Treatment.create(patientId, null, "PRIVATE_TREATMENT", "PRIVATE_DIAGNOSIS", TreatmentStatus.PLANNED, "PRIVATE_PLAN_NOTES", null, now));
        links.saveAndFlush(TreatmentTooth.link(treatment.getId(), tooth.getId(), now));
    }

    @Test void projectionExecutesOnPostgresAndReturnsOnlyTheAllowlist() throws Exception {
        String json = mvc.perform(get(path(patientId) + "/reception-summary").header("Authorization", receptionToken)
                .param("patientId", otherId.toString()).param("includeNotes", "true"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].toothFdi").value(16))
                .andExpect(jsonPath("$[0].surfaces[0].surface").value("MESIAL"))
                .andExpect(jsonPath("$[1].dentition").value("TEMPORARY"))
                .andExpect(jsonPath("$[1].surfaces.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(json).doesNotContain("PRIVATE", "notes", "treatment", "history", "author", "patientId", "createdAt");
        for (var row : mapper.readTree(json)) {
            assertThat(row.properties()).extracting(java.util.Map.Entry::getKey)
                    .containsExactlyInAnyOrder("toothFdi", "dentition", "condition", "surfaces");
            for (var surface : row.get("surfaces")) assertThat(surface.properties()).extracting(java.util.Map.Entry::getKey)
                    .containsExactlyInAnyOrder("surface", "condition");
        }
        mvc.perform(get(path(otherId) + "/reception-summary").header("Authorization", receptionToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].toothFdi").value(48));
        mvc.perform(get(path(Long.MAX_VALUE) + "/reception-summary").header("Authorization", receptionToken))
                .andExpect(status().isNotFound());
    }

    @Test void receptionCannotWriteOrReadClinicalEndpointsAndRowsStayIdentical() throws Exception {
        var before = teeth.findAll().stream().map(t -> List.of(t.getId(), t.getToothFdi(), t.getCondition(), t.getNotes())).toList();
        for (String suffix : List.of("", "/16", "/16/surfaces", "/history", "/at"))
            mvc.perform(get(path(patientId) + suffix).header("Authorization", receptionToken)).andExpect(status().isForbidden());
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE"))
            for (String suffix : List.of("", "/16", "/16/surfaces", "/16/surfaces/MESIAL", "/reception-summary"))
                mvc.perform(request(HttpMethod.valueOf(method), path(patientId) + suffix).header("Authorization", receptionToken)
                        .contentType("application/json").content("{\"condition\":\"HEALTHY\",\"notes\":\"unauthorized\"}"))
                        .andExpect(status().isForbidden());
        assertThat(teeth.findAll().stream().map(t -> List.of(t.getId(), t.getToothFdi(), t.getCondition(), t.getNotes())).toList()).isEqualTo(before);
        assertThat(surfaces.findAll().getFirst().getNotes()).isEqualTo("PRIVATE_SURFACE_NOTES");
        assertThat(links.count()).isEqualTo(1);
    }

    @Test void patientSelfAuthorizationRemainsIntactIncludingSurfaceOwnership() throws Exception {
        mvc.perform(get(path(patientId)).header("Authorization", patientToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].notes").doesNotExist()).andExpect(jsonPath("$[0].history").doesNotExist());
        mvc.perform(get(path(patientId) + "/16/surfaces").header("Authorization", patientToken)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].notes").doesNotExist());
        for (String suffix : List.of("", "/48", "/48/surfaces"))
            mvc.perform(get(path(otherId) + suffix).header("Authorization", patientToken)).andExpect(status().isNotFound());
        for (String suffix : List.of("/reception-summary", "/history", "/at"))
            mvc.perform(get(path(patientId) + suffix).header("Authorization", patientToken)).andExpect(status().isForbidden());
        mvc.perform(post(path(patientId)).header("Authorization", patientToken).contentType("application/json")
                .content("{\"toothFdi\":17,\"condition\":\"CARIES\"}")).andExpect(status().isForbidden());
    }

    User user(Role role) { return users.saveAndFlush(User.create(UUID.randomUUID() + "@ux05f.test", passwords.encode(PASSWORD), role)); }
    String login(User user) throws Exception {
        var response = mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(mapper.writeValueAsString(java.util.Map.of("email", user.getEmail(), "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse();
        return "Bearer " + mapper.readTree(response.getContentAsString()).path("accessToken").asText();
    }
    static String path(Long id) { return "/api/patients/" + id + "/odontogram"; }
}

package com.dentalclinic.appointment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

/** Fase 1A: journey with current contracts and real DDL, without a clinical session entity. */
class AppointmentClinicalContextApiTest extends AppointmentIntegrationTestSupport {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void profesionalAsignadoGuardaClinicaAntesDelCierreIndependiente(boolean assignedOwner) throws Exception {
        var actor = assignedOwner ? owner : dentistUser;
        var professional = assignedOwner ? ownerDentist : dentist;
        String token = bearer(loginToken(actor));
        var created = mockMvc.perform(post("/api/appointments")
                        .header("Authorization", bearer(loginToken(owner)))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(patient.getId(), professional.getId(),
                                at(MARTES, 9, 0), at(MARTES, 10, 0))))
                .andExpect(status().isCreated()).andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        String clinical = "/api/patients/" + patient.getId() + "/clinical";
        mockMvc.perform(get("/api/appointments/{id}", id).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.patientId").value(patient.getId()));
        mockMvc.perform(put(clinical).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"medicalBackground\":\"Antecedente\",\"allergies\":\"Penicilina\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(clinical + "/notes").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Nota durante atención\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.authorDentistId").value(professional.getId()))
                .andExpect(jsonPath("$.appointmentId").doesNotExist());
        var treatment = mockMvc.perform(post(clinical + "/treatments").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Tratamiento explícito\",\"appointmentId\":" + id + ",\"price\":500}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.appointmentId").value(id)).andReturn();
        long treatmentId = objectMapper.readTree(treatment.getResponse().getContentAsString()).get("id").asLong();
        String evolution = clinical + "/treatments/" + treatmentId + "/evolution";
        mockMvc.perform(post(evolution).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Evolución explícita\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.authorDentistId").value(professional.getId()));
        String odontogram = "/api/patients/" + patient.getId() + "/odontogram";
        mockMvc.perform(get(odontogram).header("Authorization", token)).andExpect(status().isOk());

        assertThat(appointmentRepository.findById(id).orElseThrow().getStatus().name()).isEqualTo("SCHEDULED");
        for (String path : List.of(clinical, clinical + "/notes", clinical + "/treatments", evolution, odontogram)) {
            mockMvc.perform(get(path).header("Authorization", bearer(loginToken(receptionist))))
                    .andExpect(status().isForbidden());
        }
        var tables = List.of("clinical_record", "clinical_note", "treatment", "treatment_evolution", "tooth_record");
        var before = tables.stream().map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table)).toList();
        mockMvc.perform(completeRequest(id).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        for (int i = 0; i < tables.size(); i++) {
            assertThat(jdbcTemplate.queryForList("SELECT * FROM " + tables.get(i))).isEqualTo(before.get(i));
        }
        mockMvc.perform(get(clinical + "/notes").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        assertThat(appointmentEventTypes(id)).containsExactly("APPOINTMENT_CREATED", "APPOINTMENT_COMPLETED", "FEEDBACK_REQUESTED");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name IN ('payment', 'clinical_session')", Long.class)).isZero();
    }
}

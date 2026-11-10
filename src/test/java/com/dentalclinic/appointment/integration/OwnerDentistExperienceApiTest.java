package com.dentalclinic.appointment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Existing contracts only, against the isolated Testcontainers fixture; no production changes. */
class OwnerDentistExperienceApiTest extends AppointmentIntegrationTestSupport {

    @Test
    void ownerCanFilterOwnAppointmentsWithoutLosingTheClinicAgendaOrAdminAccess() throws Exception {
        long own = book(ownerDentist.getId(), 9);
        book(dentist.getId(), 11);
        String authorization = bearer(loginToken(owner));
        mockMvc.perform(get("/api/appointments").header("Authorization", authorization)
                        .param("dentistId", ownerDentist.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(own));
        mockMvc.perform(get("/api/appointments").header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/users").header("Authorization", authorization)).andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.dentistId").value(ownerDentist.getId()));
    }

    @Test
    void patientCanSelectAndBookOwnerButAnOccupiedSlotIsNotAvailable() throws Exception {
        var directory = mockMvc.perform(get("/api/public/schedule")).andExpect(status().isOk()).andReturn();
        var dentists = objectMapper.readTree(directory.getResponse().getContentAsString()).get("dentists");
        assertThat(dentists.findValuesAsText("dentistId")).contains(ownerDentist.getId().toString());
        String body = createBody(patient.getId(), ownerDentist.getId(), at(MARTES, 9, 0), at(MARTES, 10, 0));
        mockMvc.perform(post("/api/appointments").header("Authorization", bearer(loginToken(patientUser)))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.dentistId").value(ownerDentist.getId()));
        var result = mockMvc.perform(get("/api/availability").header("Authorization", bearer(loginToken(patientUser)))
                        .param("dentistId", ownerDentist.getId().toString()).param("from", MARTES.toString())
                        .param("duration", "60"))
                .andExpect(status().isOk()).andReturn();
        var slots = objectMapper.readTree(result.getResponse().getContentAsString()).get("slots");
        assertThat(slots.findValuesAsText("start")).doesNotContain(at(MARTES, 9, 0).toString());
    }

    @Test
    void ownerWithoutConfiguredHoursHasNoSlotsAndCannotBeBooked() throws Exception {
        dentistScheduleRepository.deleteAll(dentistScheduleRepository.findByDentistIdOrderByDayOfWeekAsc(ownerDentist.getId()));
        mockMvc.perform(get("/api/availability").header("Authorization", bearer(loginToken(patientUser)))
                        .param("dentistId", ownerDentist.getId().toString()).param("from", MARTES.toString())
                        .param("duration", "60"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.slots.length()").value(0));
        mockMvc.perform(post("/api/appointments").header("Authorization", bearer(loginToken(patientUser)))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(patient.getId(), ownerDentist.getId(), at(MARTES, 9, 0), at(MARTES, 10, 0))))
                .andExpect(status().isConflict());
    }

    @Test
    void ownerOnlyCompletesAssignedAppointmentsAndTheAuditKeepsTheRealActor() throws Exception {
        long own = book(ownerDentist.getId(), 9), other = book(dentist.getId(), 11);
        String authorization = bearer(loginToken(owner));
        mockMvc.perform(completeRequest(other).header("Authorization", authorization)).andExpect(status().isForbidden());
        mockMvc.perform(completeRequest(own).header("Authorization", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(jdbcTemplate.queryForObject("SELECT actor_user_id FROM appointment_event WHERE appointment_id=? AND event_type='APPOINTMENT_COMPLETED'", Long.class, own))
                .isEqualTo(owner.getId());
    }

    private long book(Long professional, int hour) throws Exception {
        var result = mockMvc.perform(post("/api/appointments").header("Authorization", bearer(loginToken(owner)))
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(patient.getId(), professional, at(MARTES, hour, 0), at(MARTES, hour + 1, 0))))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }
}

package com.dentalclinic.appointment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.patient.domain.Patient;
import com.fasterxml.jackson.databind.JsonNode;

/** H-A12: notas administrativas solo para staff, nunca en respuestas PATIENT (PRD §11.5). */
class AppointmentNotesVisibilityApiTest extends AppointmentIntegrationTestSupport {

    private static final String INTERNAL_NOTES = "Nota interna confidencial del personal";

    @Test
    void detallePropioYEventosNoExponenNotasYConservanElContratoPublico() throws Exception {
        long id = createWithNotes(owner, patient.getId());
        String token = loginToken(patientUser);

        JsonNode body = patientResponse(mockMvc.perform(get("/api/appointments/{id}", id)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()));
        assertPublicContract(body, id);
        assertThat(body.hasNonNull("createdAt")).isTrue();

        mockMvc.perform(get("/api/appointments/{id}/events", id)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("APPOINTMENT_CREATED"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain(INTERNAL_NOTES));
        assertThat(appointmentRepository.findById(id).orElseThrow().getNotes())
                .isEqualTo(INTERNAL_NOTES);
    }

    @Test
    void listadoPropioNoExponeNotasNiCitasAjenasAunqueSePidaOtroPaciente() throws Exception {
        long ownId = createWithNotes(owner, patient.getId());
        User otherUser = newUser(Role.PATIENT);
        Patient other = patientRepository.saveAndFlush(Patient.createForUser(otherUser.getId(),
                "Otro", "Paciente", otherUser.getEmail(), null, null, null));
        long otherId = createWithNotes(owner, other.getId(), 11);
        String token = loginToken(patientUser);

        for (String url : new String[]{"/api/appointments", "/api/appointments?patientId=" + other.getId()}) {
            var result = mockMvc.perform(get(url).header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].id").value(ownId))
                    .andExpect(jsonPath("$.content[0].notes").doesNotHaveJsonPath())
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(20))
                    .andExpect(jsonPath("$.totalPages").value(1))
                    .andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain(INTERNAL_NOTES);
            assertPublicContract(objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("content").get(0), ownId);
        }
        mockMvc.perform(get("/api/appointments/{id}", otherId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/appointments/{id}/events", otherId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"OWNER", "DENTIST", "RECEPTIONIST"})
    void staffConservaNotasEnCreacionReplayDetalleYListado(Role role) throws Exception {
        User actor = switch (role) {
            case OWNER -> owner;
            case DENTIST -> dentistUser;
            case RECEPTIONIST -> receptionist;
            default -> throw new IllegalArgumentException("Solo staff");
        };
        String token = loginToken(actor);
        String key = UUID.randomUUID().toString();
        String request = bodyWithNotes(patient.getId(), 9);
        var created = create(token, key, request)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.notes").value(INTERNAL_NOTES)).andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        create(token, key, request).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.notes").value(INTERNAL_NOTES));
        mockMvc.perform(get("/api/appointments/{id}", id).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.notes").value(INTERNAL_NOTES));
        mockMvc.perform(get("/api/appointments").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].notes").value(INTERNAL_NOTES));
    }

    @Test
    void pacienteReprogramaYCancelaSinRecibirLasNotasInternas() throws Exception {
        long id = createWithNotes(owner, patient.getId());
        String token = loginToken(patientUser);
        patientResponse(mockMvc.perform(post("/api/appointments/{id}/reschedule", id)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "startAt", at(MIERCOLES, 9, 0).toString(),
                                "endAt", at(MIERCOLES, 10, 0).toString(),
                                "expectedOccurrenceVersion", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.occurrenceVersion").value(2))
                .andExpect(jsonPath("$.status").value("SCHEDULED")));
        patientResponse(mockMvc.perform(post("/api/appointments/{id}/cancel", id)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("CANCELLED")));
        mockMvc.perform(get("/api/appointments/{id}", id)
                        .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.notes").value(INTERNAL_NOTES));
    }

    @Test
    void creacionYReplayDelPacienteNoIncluyenNotas() throws Exception {
        String token = loginToken(patientUser);
        String key = UUID.randomUUID().toString();
        String request = bodyWithNotes(patient.getId(), 9);
        JsonNode created = patientResponse(create(token, key, request).andExpect(status().isCreated()));
        long id = created.path("id").asLong();
        assertPublicContract(created, id);
        JsonNode replay = patientResponse(create(token, key, request).andExpect(status().isCreated()));
        assertThat(replay).isEqualTo(created);
        assertThat(countRows("appointment")).isEqualTo(1);
        assertThat(countRows("appointment_event")).isEqualTo(1);
        assertThat(countRows("http_idempotency")).isEqualTo(1);
    }

    @Test
    void replayDeUnaRespuestaAntiguaConNotasTambienSeFiltra() throws Exception {
        String token = loginToken(patientUser);
        String key = UUID.randomUUID().toString();
        String request = bodyWithNotes(patient.getId(), 9);
        var created = create(token, key, request).andExpect(status().isCreated()).andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        // Simula una respuesta almacenada antes de corregir H-A12, solo en la BD efimera.
        var stored = httpIdempotencyRepository.findByIdentity(patientUser.getId(),
                "CREATE_APPOINTMENT", key).orElseThrow();
        stored.getResponseBody().put("notes", INTERNAL_NOTES);
        httpIdempotencyRepository.saveAndFlush(stored);

        JsonNode replay = patientResponse(create(token, key, request).andExpect(status().isCreated()));
        assertPublicContract(replay, id);
        assertThat(countRows("appointment")).isEqualTo(1);
    }

    private JsonNode patientResponse(ResultActions actions) throws Exception {
        var result = actions.andExpect(jsonPath("$.notes").doesNotHaveJsonPath()).andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(INTERNAL_NOTES);
        return objectMapper.readTree(body);
    }

    private void assertPublicContract(JsonNode body, long id) {
        assertThat(body.path("id").asLong()).isEqualTo(id);
        assertThat(body.path("patientId").asLong()).isEqualTo(patient.getId());
        assertThat(body.path("dentistId").asLong()).isEqualTo(dentist.getId());
        assertThat(body.path("patientFirstName").asText()).isEqualTo("Paciente");
        assertThat(body.path("startAt").asText()).isEqualTo(at(MARTES, 9, 0).toString());
        assertThat(body.path("endAt").asText()).isEqualTo(at(MARTES, 10, 0).toString());
        assertThat(body.path("durationMinutes").asInt()).isEqualTo(60);
        assertThat(body.path("reason").asText()).isEqualTo("Control");
        assertThat(body.path("status").asText()).isEqualTo("SCHEDULED");
        assertThat(body.path("occurrenceVersion").asInt()).isEqualTo(1);
    }

    private long createWithNotes(User actor, Long patientId) throws Exception {
        return createWithNotes(actor, patientId, 9);
    }

    private long createWithNotes(User actor, Long patientId, int hour) throws Exception {
        var result = create(loginToken(actor), UUID.randomUUID().toString(), bodyWithNotes(patientId, hour))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private String bodyWithNotes(Long patientId, int hour) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "patientId", patientId, "dentistId", dentist.getId(),
                "startAt", at(MARTES, hour, 0).toString(), "endAt", at(MARTES, hour + 1, 0).toString(),
                "reason", "Control", "notes", INTERNAL_NOTES));
    }

    private ResultActions create(String token, String key, String body) throws Exception {
        return mockMvc.perform(post("/api/appointments")
                .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}

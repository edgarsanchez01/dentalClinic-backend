package com.dentalclinic.appointment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.patient.domain.Patient;

/**
 * F6.D · Reprogramación de citas (A-07, A-09, A-20, RF-AP-18) sobre el DDL real V006.
 */
class AppointmentRescheduleApiTest extends AppointmentIntegrationTestSupport {

    private static final AtomicInteger KEYS = new AtomicInteger();

    @Test
    void reprogramarIncrementaVersionComunicaYAnulaLasIntencionesPrevias() throws Exception {
        Long id = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));

        reschedule(owner, id, at(MARTES, 11, 0), at(MARTES, 12, 0), 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurrenceVersion").value(2))
                .andExpect(jsonPath("$.startAt").value(at(MARTES, 11, 0).toString()));

        assertThat(appointmentEventTypes(id)).containsExactly("APPOINTMENT_CREATED",
                "APPOINTMENT_RESCHEDULED");
        assertThat(countRows("communication")).isEqualTo(4);
        assertThat(countRows("notification")).isEqualTo(5);
        assertThat(countRows("communication_intention")).isEqualTo(9);

        reschedule(owner, id, at(MARTES, 13, 0), at(MARTES, 14, 0), 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurrenceVersion").value(3));

        var supersedidas = jdbcTemplate.queryForList(
                "SELECT superseded_at FROM communication WHERE type = 'RESCHEDULED' "
                        + "AND appointment_id = ? ORDER BY occurrence_version, id",
                Object.class, id);
        assertThat(supersedidas).hasSize(6);
        assertThat(supersedidas.subList(0, 3)).doesNotContainNull();
        assertThat(supersedidas.subList(3, 6)).containsOnlyNulls();
    }

    @Test
    void reprogramarConUnaVersionObsoletaResponde409() throws Exception {
        Long id = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));
        reschedule(owner, id, at(MARTES, 11, 0), at(MARTES, 12, 0), 1)
                .andExpect(status().isOk());

        reschedule(owner, id, at(MARTES, 15, 0), at(MARTES, 16, 0), 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "La cita fue modificada por otra persona; recarga la vista para ver la versión actual."));

        mockMvc.perform(get("/api/appointments/{id}", id)
                        .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurrenceVersion").value(2))
                .andExpect(jsonPath("$.startAt").value(at(MARTES, 11, 0).toString()));
        assertThat(appointmentEventTypes(id)).containsExactly("APPOINTMENT_CREATED",
                "APPOINTMENT_RESCHEDULED");
    }

    /** A-20: la propia cita no se solapa consigo misma y puede volver a su slot o al vecino. */
    @Test
    void reprogramarAdmitidoElMismoSlotYElVecinoPeroNoElSolapado() throws Exception {
        Long id = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));
        Long otra = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 11, 0), at(MARTES, 12, 0));

        reschedule(owner, id, at(MARTES, 9, 0), at(MARTES, 10, 0), 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurrenceVersion").value(2));

        reschedule(owner, id, at(MARTES, 10, 0), at(MARTES, 11, 0), 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurrenceVersion").value(3));

        reschedule(owner, id, at(MARTES, 11, 0), at(MARTES, 12, 0), 3)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Ese horario ya fue tomado. Selecciona otro horario."));
        assertThat(appointmentEventTypes(otra)).containsExactly("APPOINTMENT_CREATED");
    }

    @Test
    void reprogramarFueraDelHorarioResponde409YNoCambiaNada() throws Exception {
        Long id = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));

        reschedule(owner, id, at(MARTES, 20, 0), at(MARTES, 21, 0), 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "El horario elegido cae fuera del horario de atención o en un descanso; selecciona otro."));

        mockMvc.perform(get("/api/appointments/{id}", id)
                        .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurrenceVersion").value(1))
                .andExpect(jsonPath("$.startAt").value(at(MARTES, 9, 0).toString()));
    }

    @Test
    void reprogramarEstaRestringidoAQuienPuedeOperarSobreLaCita() throws Exception {
        User otroUsuario = newUser(Role.PATIENT);
        Patient ajeno = patientRepository.saveAndFlush(Patient.createForUser(otroUsuario.getId(),
                "Otro", "Paciente", otroUsuario.getEmail(), null, null, null));
        Long ajena = createAndId(owner, ajeno.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));
        Long propia = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 11, 0), at(MARTES, 12, 0));

        User otroProfesional = newUser(Role.DENTIST);
        seedDentistFor(otroProfesional);

        reschedule(patientUser, ajena, at(MIERCOLES, 9, 0), at(MIERCOLES, 10, 0), 1)
                .andExpect(status().isNotFound());
        reschedule(otroProfesional, propia, at(MIERCOLES, 9, 0), at(MIERCOLES, 10, 0), 1)
                .andExpect(status().isForbidden());
        reschedule(receptionist, propia, at(MIERCOLES, 9, 0), at(MIERCOLES, 10, 0), 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occurrenceVersion").value(2));

        assertThat(appointmentEventTypes(ajena)).containsExactly("APPOINTMENT_CREATED");
    }

    /** D-F6-16: si el propio paciente reprograma, no se le avisa dos veces (sin parte CREATOR). */
    @Test
    void quienReprogramaNoRecibeElAvisoDeCreadorDuplicado() throws Exception {
        Long id = createAndId(patientUser, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));

        reschedule(patientUser, id, at(MARTES, 11, 0), at(MARTES, 12, 0), 1)
                .andExpect(status().isOk());

        assertThat(countRows("communication")).isEqualTo(3);
        assertThat(countRows("notification")).isEqualTo(4);
        assertThat(countRows("communication_intention")).isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM communication_intention WHERE required_role = 'CREATOR'",
                Long.class)).isZero();
    }

    @Test
    void noSeReprogramaUnaCitaQueNoEstaProgramada() throws Exception {
        Long id = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));
        mockMvc.perform(post("/api/appointments/{id}/cancel", id)
                        .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk());

        reschedule(owner, id, at(MIERCOLES, 9, 0), at(MIERCOLES, 10, 0), 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "La cita ya no está programada (estado CANCELLED)."));
    }

    // ------------------------------------------------------------- helpers

    private org.springframework.test.web.servlet.ResultActions reschedule(User actor, Long id,
            Instant start, Instant end, int expectedVersion) throws Exception {
        return mockMvc.perform(post("/api/appointments/{id}/reschedule", id)
                .header("Authorization", bearer(loginToken(actor)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of(
                        "startAt", start.toString(),
                        "endAt", end.toString(),
                        "expectedOccurrenceVersion", expectedVersion))));
    }

    private Long createAndId(User actor, Long patientId, Long dentistId, Instant start,
            Instant end) throws Exception {
        var result = mockMvc.perform(post("/api/appointments")
                        .header("Authorization", bearer(loginToken(actor)))
                        .header("Idempotency-Key", "k-" + KEYS.incrementAndGet() + "-"
                                + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(patientId, dentistId, start, end)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }
}

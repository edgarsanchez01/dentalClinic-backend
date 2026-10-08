package com.dentalclinic.appointment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.patient.domain.Patient;

/**
 * F6.D · Ciclo de vida y autorización de las transiciones de cita (§14.6, §17.5, A-09, A-12, A-18,
 * RF-AP-14) sobre el DDL real V006.
 */
class AppointmentLifecycleApiTest extends AppointmentIntegrationTestSupport {

    private static final AtomicInteger KEYS = new AtomicInteger();

    @Test
    void trayectoriaLlegadaComienzoYFinalizacionDelProfesionalAsignado() throws Exception {
        Long id = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));

        mockMvc.perform(post("/api/appointments/{id}/arrive", id)
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventType").value("PATIENT_ARRIVED"));

        mockMvc.perform(post("/api/appointments/{id}/start", id)
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventType").value("APPOINTMENT_STARTED"));

        mockMvc.perform(post("/api/appointments/{id}/complete", id)
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").isNotEmpty());

        mockMvc.perform(get("/api/appointments/{id}/events", id)
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("APPOINTMENT_CREATED"))
                .andExpect(jsonPath("$[1].eventType").value("PATIENT_ARRIVED"))
                .andExpect(jsonPath("$[2].eventType").value("APPOINTMENT_STARTED"))
                .andExpect(jsonPath("$[3].eventType").value("APPOINTMENT_COMPLETED"));

        // §14.6: llegada, inicio y completado son solo notificación in-app (sin Communication).
        assertThat(countRows("communication")).isEqualTo(1);
        assertThat(countRows("communication_intention")).isEqualTo(3);
        assertThat(countRows("notification")).isEqualTo(5);
    }

    @Test
    void laLlegadaYElComienzoSoloSeRegistranUnaVez() throws Exception {
        Long id = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));

        mockMvc.perform(post("/api/appointments/{id}/arrive", id)
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/appointments/{id}/arrive", id)
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/appointments/{id}/start", id)
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/appointments/{id}/start", id)
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isConflict());

        assertThat(appointmentEventTypes(id)).containsExactly("APPOINTMENT_CREATED",
                "PATIENT_ARRIVED", "APPOINTMENT_STARTED");
    }

    @Test
    void laTrayectoriaEsSoloDelProfesionalAsignadoODeLaAdministracionAsignada() throws Exception {
        User otroProfesional = newUser(Role.DENTIST);
        var otroDentist = seedDentistFor(otroProfesional);
        Long id = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));

        mockMvc.perform(post("/api/appointments/{id}/arrive", id)
                        .header("Authorization", bearer(loginToken(receptionist))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/appointments/{id}/arrive", id)
                        .header("Authorization", bearer(loginToken(patientUser))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/appointments/{id}/arrive", id)
                        .header("Authorization", bearer(loginToken(otroProfesional))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/appointments/{id}/complete", id)
                        .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        "Esta cita no es tuya: solo el profesional asignado puede operar sobre ella."));

        assertThat(otroDentist.getId()).isNotEqualTo(dentist.getId());
        assertThat(appointmentEventTypes(id)).containsExactly("APPOINTMENT_CREATED");
    }

    /** A-12: el OWNER sí ejecuta la trayectoria cuando su perfil de dentista es el asignado. */
    @Test
    void elOwnerConPerfilAsignadoSiPuedeCompletar() throws Exception {
        Long id = createAndId(owner, patient.getId(), ownerDentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));

        mockMvc.perform(post("/api/appointments/{id}/complete", id)
                        .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    /** A-18: no-show solo OWNER/RECEPTIONIST y solo una vez pasada la hora de inicio. */
    @Test
    void elNoShowSoloLoDeclaraStaffYPocoDespuesDeEmpezar() throws Exception {
        Long id = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));

        mockMvc.perform(post("/api/appointments/{id}/no-show", id)
                        .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Aún no puede declararse ausencia: la cita todavía no comenzó."));

        TestTime.advance(Duration.between(TestTime.BASE, Instant.parse("2026-10-13T09:30:00Z")));

        mockMvc.perform(post("/api/appointments/{id}/no-show", id)
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        "Solo la recepción o la administración declaran un no-show."));
        mockMvc.perform(post("/api/appointments/{id}/no-show", id)
                        .header("Authorization", bearer(loginToken(patientUser))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/appointments/{id}/no-show", id)
                        .header("Authorization", bearer(loginToken(receptionist))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_SHOW"));

        assertThat(appointmentEventTypes(id)).containsExactly("APPOINTMENT_CREATED",
                "APPOINTMENT_NO_SHOW");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT title FROM notification WHERE appointment_id = ? ORDER BY id DESC LIMIT 1",
                String.class, id)).isEqualTo("Cita no realizada");

        mockMvc.perform(post("/api/appointments/{id}/cancel", id)
                        .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isConflict());
    }

    @Test
    void desdeUnEstadoTerminalNoHayTransiciones() throws Exception {
        Long id = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));
        String token = loginToken(dentistUser);

        mockMvc.perform(post("/api/appointments/{id}/complete", id)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/appointments/{id}/cancel", id)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "La cita ya no está programada (estado COMPLETED)."));
        mockMvc.perform(post("/api/appointments/{id}/complete", id)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/appointments/{id}/arrive", id)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict());
    }

    /** RF-AP: el paciente respeta la antelación de cancelación; la recepción, no. */
    @Test
    void cancelarPorPacienteRespetaLaAntelacionPeroElStaffNo() throws Exception {
        Instant mismoDia = at(MARTES.minusDays(7), 17, 0);
        Long id = createAndId(owner, patient.getId(), dentist.getId(), mismoDia,
                mismoDia.plus(Duration.ofHours(1)));

        mockMvc.perform(post("/api/appointments/{id}/cancel", id)
                        .header("Authorization", bearer(loginToken(patientUser))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Solo puedes cancelar hasta 12 horas antes de la cita."));

        mockMvc.perform(post("/api/appointments/{id}/cancel", id)
                        .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(appointmentEventTypes(id)).containsExactly("APPOINTMENT_CREATED",
                "APPOINTMENT_CANCELLED");
        // A-14: una cita CANCELLED ya no ocupa el slot.
        createAndId(owner, patient.getId(), dentist.getId(), mismoDia,
                mismoDia.plus(Duration.ofHours(1))).longValue();
    }

    @Test
    void nadieCancelaCitasAjenas() throws Exception {
        User otroUsuario = newUser(Role.PATIENT);
        Patient ajeno = patientRepository.saveAndFlush(Patient.createForUser(otroUsuario.getId(),
                "Otro", "Paciente", otroUsuario.getEmail(), null, null, null));
        Long ajena = createAndId(owner, ajeno.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));
        Long propia = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 11, 0), at(MARTES, 12, 0));

        User otroProfesional = newUser(Role.DENTIST);
        seedDentistFor(otroProfesional);

        mockMvc.perform(post("/api/appointments/{id}/cancel", ajena)
                        .header("Authorization", bearer(loginToken(patientUser))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/appointments/{id}/cancel", ajena)
                        .header("Authorization", bearer(loginToken(otroProfesional))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/appointments/{id}/cancel", propia)
                        .header("Authorization", bearer(loginToken(patientUser))))
                .andExpect(status().isOk());

        assertThat(appointmentEventTypes(ajena)).containsExactly("APPOINTMENT_CREATED");
    }

    // ------------------------------------------------------------- helpers

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

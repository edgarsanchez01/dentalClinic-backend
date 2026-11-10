package com.dentalclinic.appointment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completaDirectamenteSinLlegadaInicioNiEscriturasClinicas(boolean hasClinicalData) throws Exception {
        Long id = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));
        if (hasClinicalData) {
            Long recordId = jdbcTemplate.queryForObject("INSERT INTO clinical_record "
                    + "(patient_id, allergies) VALUES (?, 'Alergia registrada') RETURNING id",
                    Long.class, patient.getId());
            jdbcTemplate.update("INSERT INTO clinical_note "
                    + "(clinical_record_id, author_dentist_id, content, created_at) "
                    + "VALUES (?, ?, 'Nota previa', now())", recordId, dentist.getId());
            jdbcTemplate.update("INSERT INTO treatment "
                    + "(patient_id, appointment_id, description, status, price, created_at) "
                    + "VALUES (?, ?, 'Tratamiento previsto', 'PLANNED', 75.1234, now())",
                    patient.getId(), id);
            Long toothId = jdbcTemplate.queryForObject("INSERT INTO tooth_record "
                    + "(patient_id, tooth_fdi, condition, notes, created_at) "
                    + "VALUES (?, 11, 'CARIES', 'Observación previa', now()) RETURNING id",
                    Long.class, patient.getId());
            jdbcTemplate.update("INSERT INTO tooth_history "
                    + "(tooth_record_id, new_condition, actor_user_id, changed_at) "
                    + "VALUES (?, 'CARIES', ?, now())", toothId, dentistUser.getId());
        }
        List<String> clinicalTables = List.of("clinical_record", "clinical_note", "treatment",
                "treatment_evolution", "treatment_tooth", "tooth_record", "tooth_history",
                "tooth_surface_record", "tooth_surface_history");
        List<List<Map<String, Object>>> before = clinicalTables.stream()
                .map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table)).toList();
        var booking = appointmentRepository.findById(id).orElseThrow();

        mockMvc.perform(completeRequest(id)
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").value(TestTime.now().toString()))
                .andExpect(jsonPath("$.occurrenceVersion").value(1))
                .andExpect(jsonPath("$.service.id").value(booking.getServiceId()))
                .andExpect(jsonPath("$.service.durationMinutes").value(60));

        mockMvc.perform(get("/api/appointments/{id}/events", id)
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("APPOINTMENT_CREATED"))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[1].eventType").value("APPOINTMENT_COMPLETED"))
                .andExpect(jsonPath("$[1].actorUserId").value(dentistUser.getId()))
                .andExpect(jsonPath("$[1].occurrenceVersion").value(1));

        // Fase 5C: conserva aviso y añade invitación en outbox, sin escrituras clínicas/financieras.
        assertThat(countRows("communication")).isEqualTo(2);
        assertThat(countRows("communication_intention")).isEqualTo(4);
        assertThat(countRows("notification")).isEqualTo(3);
        for (int i = 0; i < clinicalTables.size(); i++) {
            assertThat(jdbcTemplate.queryForList("SELECT * FROM " + clinicalTables.get(i)))
                    .as(clinicalTables.get(i)).isEqualTo(before.get(i));
        }
    }

    @Test
    void lasAccionesRetiradasNoEscribenEventosNiNotificaciones() throws Exception {
        Long id = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));
        for (String action : List.of("arrive", "start")) {
            mockMvc.perform(post("/api/appointments/{id}/{action}", id, action)
                            .header("Authorization", bearer(loginToken(dentistUser))))
                    .andExpect(status().isNotFound());
        }
        assertThat(appointmentEventTypes(id)).containsExactly("APPOINTMENT_CREATED");
        assertThat(countRows("notification")).isEqualTo(2);
        assertThat(appointmentRepository.findById(id).orElseThrow().getStatus().name())
                .isEqualTo("SCHEDULED");
    }

    @Test
    void conservaYExponeLosEventosHistoricosAlCompletar() throws Exception {
        Long id = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));
        for (String type : List.of("PATIENT_ARRIVED", "APPOINTMENT_STARTED")) {
            jdbcTemplate.update("INSERT INTO appointment_event "
                    + "(appointment_id, event_type, actor_user_id, occurrence_version, metadata) "
                    + "VALUES (?, ?, ?, 1, '{\"legacy\":true}'::jsonb)", id, type, dentistUser.getId());
        }
        var history = jdbcTemplate.queryForList(
                "SELECT * FROM appointment_event WHERE appointment_id = ? ORDER BY id", id);
        mockMvc.perform(completeRequest(id)
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForList(
                "SELECT * FROM appointment_event WHERE appointment_id = ? ORDER BY id", id)
                .subList(0, history.size())).isEqualTo(history);
        mockMvc.perform(get("/api/appointments/{id}/events", id)
                        .header("Authorization", bearer(loginToken(patientUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[1].eventType").value("PATIENT_ARRIVED"))
                .andExpect(jsonPath("$[1].metadata.legacy").value(true))
                .andExpect(jsonPath("$[2].eventType").value("APPOINTMENT_STARTED"))
                .andExpect(jsonPath("$[3].eventType").value("APPOINTMENT_COMPLETED"));
    }

    /** T4.4 mantiene la política existente: completar no tiene una barrera temporal. */
    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 30, 60, 1440})
    void completarNoDependeDeLaHoraProgramada(int minutesFromStart) throws Exception {
        Instant start = at(MARTES, 9, 0);
        Long id = createAndId(owner, patient.getId(), dentist.getId(), start,
                start.plus(Duration.ofHours(1)));
        TestTime.advance(Duration.between(TestTime.now(), start.plus(Duration.ofMinutes(minutesFromStart))));
        mockMvc.perform(completeRequest(id)
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").value(TestTime.now().toString()));
    }

    @Test
    void completarEsSoloDelProfesionalAsignadoODeLaAdministracionAsignada() throws Exception {
        User otroProfesional = newUser(Role.DENTIST);
        var otroDentist = seedDentistFor(otroProfesional);
        Long id = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));

        mockMvc.perform(completeRequest(id)
                        .header("Authorization", bearer(loginToken(receptionist))))
                .andExpect(status().isForbidden());
        mockMvc.perform(completeRequest(id)
                        .header("Authorization", bearer(loginToken(patientUser))))
                .andExpect(status().isForbidden());
        mockMvc.perform(completeRequest(id)
                        .header("Authorization", bearer(loginToken(otroProfesional))))
                .andExpect(status().isNotFound());
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

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "CANCELLED", "NO_SHOW"})
    void desdeUnEstadoTerminalNoHayTransiciones(String terminal) throws Exception {
        Long id = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));
        TestTime.advance(Duration.between(TestTime.now(), at(MARTES, 9, 0)));
        String token = loginToken(dentistUser);
        String action = switch (terminal) {
            case "CANCELLED" -> "cancel";
            case "NO_SHOW" -> "no-show";
            default -> "complete";
        };
        mockMvc.perform(post("/api/appointments/{id}/{action}", id, action)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedOccurrenceVersion\":1}")
                        .header("Authorization", bearer(terminal.equals("NO_SHOW") ? loginToken(owner) : token)))
                .andExpect(status().isOk());
        var history = appointmentEventTypes(id);
        long notifications = countRows("notification");

        mockMvc.perform(post("/api/appointments/{id}/cancel", id)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "La cita ya no está programada (estado " + terminal + ")."));
        mockMvc.perform(completeRequest(id)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/appointments/{id}/reschedule", id)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startAt\":\"2026-10-14T09:00:00Z\",\"endAt\":\"2026-10-14T10:00:00Z\",\"expectedOccurrenceVersion\":1}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/appointments/{id}/no-show", id)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict());
        assertThat(appointmentEventTypes(id)).isEqualTo(history);
        assertThat(countRows("notification")).isEqualTo(notifications);
        assertThat(appointmentRepository.findById(id).orElseThrow().getStatus().name()).isEqualTo(terminal);
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

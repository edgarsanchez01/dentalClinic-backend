package com.dentalclinic.odontogram.api;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import com.dentalclinic.appointment.application.AppointmentClinicalContextService.Selection;
import com.dentalclinic.appointment.domain.AppointmentStatus;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.odontogram.domain.*;
import com.dentalclinic.odontogram.application.OdontogramService.ToothData;
import com.dentalclinic.odontogram.infrastructure.repository.*;
import com.fasterxml.jackson.databind.JsonNode;

/** Actual SQL V001–V015, HTTP, permissions, atomic rollback and honest historical reconstruction. */
class OdontogramHistoryApiTest extends OdontogramHistoryTestSupport {
    @MockitoSpyBean private ToothNotesHistoryRepository notesWriter;
    @MockitoSpyBean private ToothSurfaceNotesHistoryRepository surfaceNotesWriter;

    @Test void cuatroOperacionesRegistranCondicionNotasYVinculosConElMismoContexto() throws Exception {
        Long appointment = appointment(9), first = treatment(), second = treatment();
        var selection = new Selection(appointment, 1);
        String initial = body(ToothCondition.HEALTHY, "Inicial", List.of(first), selection);
        mockMvc.perform(post(path()).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(initial.replaceFirst("\\{", "{\"toothFdi\":21,"))).andExpect(status().isCreated());
        mockMvc.perform(put(path() + "/21").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(body(ToothCondition.RESTORED, "Nueva", List.of(second), selection)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.history.length()").value(2));
        mockMvc.perform(post(path() + "/21/surfaces").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"surface\":\"MESIAL\",\"condition\":\"CARIES\",\"notes\":\"Inicial S\",\"appointmentContext\":{\"appointmentId\":"
                        + appointment + ",\"occurrenceVersion\":1}}"))
                .andExpect(status().isCreated());
        mockMvc.perform(put(path() + "/21/surfaces/MESIAL").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(body(ToothCondition.RESTORED, "Nueva S", List.of(), selection))).andExpect(status().isOk());
        for (String table : tables()) {
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + table
                    + " WHERE appointment_id IS NULL OR appointment_occurrence_version<>1"
                    + " OR appointment_status_at_registration<>'SCHEDULED'", Long.class)).as(table).isZero();
        }
        mockMvc.perform(get(path() + "/history").header("Authorization", token).param("appointmentId", appointment.toString())
                .param("occurrenceVersion", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(11))
                .andExpect(jsonPath("$.content[0].source").value("NOTES_SURFACE"))
                .andExpect(jsonPath("$.content[0].actorUserId").value(dentistUser.getId()))
                .andExpect(jsonPath("$.content[0].occurrence.occurrenceVersion").value(1));
        assertThat(countRows("appointment_occurrence_snapshot")).isEqualTo(1);
    }

    @Test void noopNoEmiteEventosNiReinsertaVinculosNiCambiaUpdatedAt() throws Exception {
        Long treatment = treatment();
        createTooth("Inicial", List.of(treatment), null);
        var before = jdbcTemplate.queryForList("SELECT * FROM treatment_tooth");
        var toothBefore = jdbcTemplate.queryForList("SELECT * FROM tooth_record");
        mockMvc.perform(put(path() + "/21").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(body(ToothCondition.HEALTHY, "  Inicial  ", Arrays.asList(treatment, treatment, null), new Selection(appointment(9), 1))))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForList("SELECT * FROM treatment_tooth")).isEqualTo(before);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM tooth_record")).isEqualTo(toothBefore);
        assertThat(eventCount()).isEqualTo(3);
        assertThat(countRows("appointment_occurrence_snapshot")).isZero();
    }

    @Test void cambioSoloDeVinculosRegistraDiffRealYConservaLasCondiciones() throws Exception {
        Long first = treatment(), second = treatment();
        createTooth("Notas", List.of(first), null);
        mockMvc.perform(put(path() + "/21").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(body(ToothCondition.HEALTHY, "Notas", List.of(second), null))).andExpect(status().isOk());
        assertThat(countRows("tooth_history")).isEqualTo(1);
        assertThat(countRows("tooth_notes_history")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT change_kind FROM tooth_treatment_history ORDER BY id", String.class))
                .containsExactly("ADD", "REMOVE", "ADD");
        assertThat(jdbcTemplate.queryForObject("SELECT treatment_id FROM treatment_tooth", Long.class)).isEqualTo(second);
    }

    @Test void filtrosConPrecisionSubmicrosegundoNoIncluyenEventosFueraDelIntervalo() throws Exception {
        createTooth("Nota", List.of(), null);
        mockMvc.perform(get(path() + "/history").header("Authorization", token)
                .param("from", TestTime.now().plusNanos(1).toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get(path() + "/history").header("Authorization", token)
                .param("to", TestTime.now().minusNanos(1).toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @ParameterizedTest @EnumSource(AppointmentStatus.class)
    void estadosDeCitaControlanLasCuatroEscriturasContextuales(AppointmentStatus state) throws Exception {
        Long appointment = appointment(9);
        var a = appointmentRepository.findById(appointment).orElseThrow();
        if (state == AppointmentStatus.COMPLETED) a.complete(TestTime.now());
        if (state == AppointmentStatus.CANCELLED) a.cancel(TestTime.now());
        if (state == AppointmentStatus.NO_SHOW) a.markNoShow(TestTime.now());
        appointmentRepository.saveAndFlush(a);
        var selection = new Selection(appointment, 1);
        createTooth("General", List.of(), null);
        odontogram.createSurface(patient.getId(), 21,
                new com.dentalclinic.odontogram.application.OdontogramService.SurfaceData(ToothSurface.MESIAL, ToothCondition.HEALTHY, "General"), actor());
        boolean allowed = state == AppointmentStatus.SCHEDULED || state == AppointmentStatus.COMPLETED;
        String data = body(ToothCondition.CARIES, "Contextual", List.of(), selection);
        var update = mockMvc.perform(put(path() + "/21").header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(data));
        update.andExpect(allowed ? status().isOk() : status().isConflict());
        mockMvc.perform(put(path() + "/21/surfaces/MESIAL").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(data))
                .andExpect(allowed ? status().isOk() : status().isConflict());
        mockMvc.perform(post(path()).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(data.replaceFirst("\\{", "{\"toothFdi\":22,")))
                .andExpect(allowed ? status().isCreated() : status().isConflict());
        mockMvc.perform(post(path() + "/21/surfaces").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(data.replaceFirst("\\{", "{\"surface\":\"DISTAL\",")))
                .andExpect(allowed ? status().isCreated() : status().isConflict());
        if (allowed) assertThat(jdbcTemplate.queryForList("SELECT appointment_status_at_registration FROM tooth_history"
                + " WHERE appointment_id IS NOT NULL", String.class)).containsOnly(state.name());
        else { assertThat(countRows("appointment_occurrence_snapshot")).isZero(); assertThat(eventCount()).isEqualTo(4); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"appointmentId\":1}", "{\"appointmentId\":1,\"occurrenceVersion\":1.5}",
            "{\"appointmentId\":\"1\",\"occurrenceVersion\":1}", "{\"appointmentId\":0,\"occurrenceVersion\":1}"})
    void contextoIncompletoOCoercionesRechazadasSinEscritura(String context) throws Exception {
        mockMvc.perform(post(path()).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"toothFdi\":21,\"condition\":\"HEALTHY\",\"appointmentContext\":" + context + "}"))
                .andExpect(status().isBadRequest());
        assertThat(eventCount()).isZero();
        assertThat(countRows("appointment_occurrence_snapshot")).isZero();
    }

    @Test void versionObsoleta409NoReasignaSnapshotYGeneralSigueValido() throws Exception {
        Long appointment = appointment(9);
        createTooth("Primera", List.of(), new Selection(appointment, 1));
        var frozen = jdbcTemplate.queryForList("SELECT * FROM appointment_occurrence_snapshot");
        jdbcTemplate.update("UPDATE appointment SET occurrence_version=2 WHERE id=?", appointment);
        mockMvc.perform(put(path() + "/21").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(body(ToothCondition.CARIES, "Obsoleta", List.of(), new Selection(appointment, 1))))
                .andExpect(status().isConflict());
        assertThat(eventCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM appointment_occurrence_snapshot")).isEqualTo(frozen);
        mockMvc.perform(put(path() + "/21").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(body(ToothCondition.CARIES, "General", List.of(), null))).andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT notes FROM tooth_record", String.class)).isEqualTo("General");
    }

    @Test void capturaNoEsFinDeAtencionYLosGetNoEscriben() throws Exception {
        Long appointment = appointment(9);
        createTooth("Antes", List.of(), null);
        TestTime.advance(Duration.ofSeconds(1));
        odontogram.update(patient.getId(), 21, new ToothData(21, ToothCondition.CROWN, "Captura", List.of()), actor(), new Selection(appointment, 1));
        TestTime.advance(Duration.ofSeconds(1));
        odontogram.update(patient.getId(), 21, new ToothData(21, ToothCondition.RESTORED, "Después", List.of()), actor(), new Selection(appointment, 1));
        long count = eventCount();
        var frozen = jdbcTemplate.queryForList("SELECT * FROM appointment_occurrence_snapshot");
        mockMvc.perform(get(path() + "/at").header("Authorization", token)
                .param("appointmentId", appointment.toString()).param("occurrenceVersion", "1").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reference.occurrence.kind").value("FROZEN"))
                .andExpect(jsonPath("$.stateAtCapture.teeth[0].condition.value").value("CROWN"))
                .andExpect(jsonPath("$.attributedEvents.totalElements").value(4))
                .andExpect(jsonPath("$.attributedEvents.content.length()").value(1))
                .andExpect(jsonPath("$.currentState.teeth[0].condition").value("RESTORED"));
        mockMvc.perform(get(path() + "/history").header("Authorization", token)).andExpect(status().isOk());
        assertThat(eventCount()).isEqualTo(count);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM appointment_occurrence_snapshot")).isEqualTo(frozen);
    }

    @Test void ocurrenciasSinCapturaNoFabricanEstadoHistorico() throws Exception {
        Long appointment = appointment(9);
        createTooth("Actual", List.of(), null);
        mockMvc.perform(get(path() + "/at").header("Authorization", token).param("appointmentId", appointment.toString())
                .param("occurrenceVersion", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reference.occurrence.kind").value("CURRENT_UNFROZEN"))
                .andExpect(jsonPath("$.stateAtCapture").value(nullValue()))
                .andExpect(jsonPath("$.reconstructionAvailable").value(false));
        jdbcTemplate.update("INSERT INTO appointment_event(appointment_id,event_type,actor_user_id,occurrence_version)"
                + " VALUES (?,'APPOINTMENT_CREATED',?,1)", appointment, owner.getId());
        jdbcTemplate.update("UPDATE appointment SET occurrence_version=2 WHERE id=?", appointment);
        mockMvc.perform(get(path() + "/at").header("Authorization", token).param("appointmentId", appointment.toString())
                .param("occurrenceVersion", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reference.occurrence.kind").value("HISTORICAL_UNKNOWN"))
                .andExpect(jsonPath("$.stateAtCapture").value(nullValue()))
                .andExpect(jsonPath("$.currentState.teeth[0].notes").value("Actual"));
        assertThat(countRows("appointment_occurrence_snapshot")).isZero();
    }

    @Test void datosLegadosNoSeProyectanDesdeElEstadoActualYLimpiarNullEsConocido() throws Exception {
        Long treatment = treatment();
        Long tooth = jdbcTemplate.queryForObject("INSERT INTO tooth_record(patient_id,tooth_fdi,condition,notes,created_at)"
                + " VALUES (?,21,'HEALTHY','Actual sin historia',?) RETURNING id", Long.class, patient.getId(), java.sql.Timestamp.from(TestTime.now().minusSeconds(10)));
        jdbcTemplate.update("INSERT INTO tooth_history(tooth_record_id,new_condition,actor_user_id,changed_at) VALUES (?,'HEALTHY',?,?)",
                tooth, dentistUser.getId(), java.sql.Timestamp.from(TestTime.now().minusSeconds(10)));
        jdbcTemplate.update("INSERT INTO treatment_tooth(treatment_id,tooth_record_id,created_at) VALUES (?,?,?)",
                treatment, tooth, java.sql.Timestamp.from(TestTime.now().minusSeconds(10)));
        var before = TestTime.now().minusSeconds(1).toString();
        mockMvc.perform(get(path() + "/at").header("Authorization", token).param("at", before)).andExpect(status().isOk())
                .andExpect(jsonPath("$.teeth[0].notes.knowledge").value("UNKNOWN"))
                .andExpect(jsonPath("$.teeth[0].notes.value").value(nullValue()))
                .andExpect(jsonPath("$.teeth[0].treatments.knowledge").value("UNKNOWN"))
                .andExpect(jsonPath("$.teeth[0].treatments.treatmentIds").value(nullValue()));
        odontogram.update(patient.getId(), 21, new ToothData(21, ToothCondition.HEALTHY, null, List.of(treatment)), actor());
        mockMvc.perform(get(path() + "/at").header("Authorization", token).param("at", TestTime.now().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.teeth[0].notes.knowledge").value("KNOWN"))
                .andExpect(jsonPath("$.teeth[0].notes.value").value(nullValue()));
        mockMvc.perform(get(path() + "/at").header("Authorization", token).param("at", before)).andExpect(status().isOk())
                .andExpect(jsonPath("$.teeth[0].notes.knowledge").value("UNKNOWN"));
    }

    @ParameterizedTest @EnumSource(Role.class)
    void permisosDeHistorialYAtNoAmplianResumenPaciente(Role role) throws Exception {
        var user = switch (role) { case OWNER -> owner; case DENTIST -> dentistUser; case RECEPTIONIST -> receptionist; case PATIENT -> patientUser; };
        String authorization = bearer(loginToken(user));
        for (String endpoint : List.of("/history", "/at?at=" + TestTime.now())) {
            var result = mockMvc.perform(get(path() + endpoint).header("Authorization", authorization));
            result.andExpect(role == Role.OWNER || role == Role.DENTIST ? status().isOk() : status().isForbidden());
        }
        if (role == Role.PATIENT) mockMvc.perform(get(path()).header("Authorization", authorization)).andExpect(status().isOk());
    }

    @Test void anonimo401FiltrosInvalidos400YCitaAjena404() throws Exception {
        mockMvc.perform(get(path() + "/history")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path() + "/at").param("at", TestTime.now().toString())).andExpect(status().isUnauthorized());
        for (String query : List.of("/history?size=101", "/history?page=-1", "/history?toothFdi=19",
                "/history?appointmentId=1", "/history?from=2026-02-02T10:00:00Z&to=2026-01-01T10:00:00Z",
                "/at", "/at?at=invalid", "/at?at=" + TestTime.now() + "&appointmentId=1&occurrenceVersion=1")) {
            mockMvc.perform(get(path() + query).header("Authorization", token)).andExpect(status().isBadRequest());
        }
        Long appointment = appointment(9);
        var foreign = seedPatientWithoutChannel();
        mockMvc.perform(get("/api/patients/" + foreign.getId() + "/odontogram/history")
                .header("Authorization", token).param("appointmentId", appointment.toString()).param("occurrenceVersion", "1"))
                .andExpect(status().isNotFound());
    }

    @Test void usuarioClinicoSinPerfilPuedeEscribirYQuedaComoAutorUsuario() throws Exception {
        var user = newUser(Role.DENTIST);
        var actor = new AuthenticatedUser(user.getId(), Role.DENTIST);
        odontogram.create(patient.getId(), new ToothData(21, ToothCondition.HEALTHY, "Sin perfil", List.of()), actor, new Selection(appointment(9), 1));
        mockMvc.perform(get(path() + "/history").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].actorUserId").value(user.getId()))
                .andExpect(jsonPath("$.content[0].actorName").value("Usuario #" + user.getId()));
    }

    @Test void falloDeEventoRevierteEstadoVinculosHistoriaYSnapshot() throws Exception {
        Long first = treatment(), second = treatment();
        createTooth("Anterior", List.of(first), null);
        var before = jdbcTemplate.queryForList("SELECT * FROM tooth_record");
        var links = jdbcTemplate.queryForList("SELECT * FROM treatment_tooth");
        long count = eventCount();
        Long appointment = appointment(9);
        doThrow(new DataIntegrityViolationException("Fallo sintético de evento")).when(notesWriter).save(any());
        mockMvc.perform(put(path() + "/21").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(body(ToothCondition.CARIES, "Fallida", List.of(second), new Selection(appointment, 1))))
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForList("SELECT * FROM tooth_record")).isEqualTo(before);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM treatment_tooth")).isEqualTo(links);
        assertThat(eventCount()).isEqualTo(count);
        assertThat(countRows("appointment_occurrence_snapshot")).isZero();
    }

    @Test void falloDeEventoSuperficieRevierteSusDosCambiosYSnapshot() throws Exception {
        createTooth(null, List.of(), null);
        odontogram.createSurface(patient.getId(), 21,
                new com.dentalclinic.odontogram.application.OdontogramService.SurfaceData(ToothSurface.MESIAL, ToothCondition.HEALTHY, "Anterior"), actor());
        var before = jdbcTemplate.queryForList("SELECT * FROM tooth_surface_record");
        long count = eventCount();
        doThrow(new DataIntegrityViolationException("Fallo sintético de superficie")).when(surfaceNotesWriter).save(any());
        mockMvc.perform(put(path() + "/21/surfaces/MESIAL").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(body(ToothCondition.CARIES, "Fallida", List.of(), new Selection(appointment(9), 1))))
                .andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForList("SELECT * FROM tooth_surface_record")).isEqualTo(before);
        assertThat(eventCount()).isEqualTo(count);
        assertThat(countRows("appointment_occurrence_snapshot")).isZero();
    }

    @Test void unionPaginadaDesempataCincoFuentesConIdYFechaIdenticos() throws Exception {
        Long tooth = createTooth(null, List.of(), null), treatment = treatment();
        Long surface = odontogram.createSurface(patient.getId(), 21,
                new com.dentalclinic.odontogram.application.OdontogramService.SurfaceData(ToothSurface.MESIAL, ToothCondition.HEALTHY, null), actor()).id();
        var at = TestTime.now().plusSeconds(10);
        var time = java.sql.Timestamp.from(at);
        jdbcTemplate.update("INSERT INTO tooth_history(id,tooth_record_id,new_condition,actor_user_id,changed_at) VALUES (1000000,?,'CARIES',?,?)", tooth, dentistUser.getId(), time);
        jdbcTemplate.update("INSERT INTO tooth_notes_history(id,tooth_record_id,new_notes,actor_user_id,changed_at) VALUES (1000000,?,'N',?,?)", tooth, dentistUser.getId(), time);
        jdbcTemplate.update("INSERT INTO tooth_treatment_history(id,tooth_record_id,treatment_id,change_kind,actor_user_id,changed_at) VALUES (1000000,?,?,'ADD',?,?)", tooth, treatment, dentistUser.getId(), time);
        jdbcTemplate.update("INSERT INTO tooth_surface_history(id,tooth_surface_record_id,new_condition,actor_user_id,changed_at) VALUES (1000000,?,'CARIES',?,?)", surface, dentistUser.getId(), time);
        jdbcTemplate.update("INSERT INTO tooth_surface_notes_history(id,tooth_surface_record_id,new_notes,actor_user_id,changed_at) VALUES (1000000,?,'S',?,?)", surface, dentistUser.getId(), time);
        List<String> sources = new ArrayList<>();
        for (int page = 0; page < 5; page++) {
            var response = mockMvc.perform(get(path() + "/history").header("Authorization", token)
                    .param("from", at.toString()).param("to", at.toString()).param("size", "1").param("page", "" + page))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(5))
                    .andExpect(jsonPath("$.content[0].id").value(1000000)).andReturn();
            sources.add(objectMapper.readTree(response.getResponse().getContentAsString()).path("content").get(0).path("source").asText());
        }
        assertThat(sources).containsExactly("NOTES_SURFACE", "CONDITION_SURFACE", "TREATMENT_LINK", "NOTES_TOOTH", "CONDITION_TOOTH");
    }

    private List<String> tables() { return List.of("tooth_history", "tooth_surface_history", "tooth_notes_history", "tooth_surface_notes_history", "tooth_treatment_history"); }
    private long eventCount() { return tables().stream().mapToLong(this::countRows).sum(); }
}

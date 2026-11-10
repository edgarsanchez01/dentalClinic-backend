package com.dentalclinic.appointment.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.dentalclinic.appointment.application.AppointmentClinicalContextService.Selection;
import com.dentalclinic.appointment.application.AppointmentService;
import com.dentalclinic.appointment.domain.AppointmentStatus;
import com.dentalclinic.clinical.application.ClinicalService;
import com.dentalclinic.clinical.application.TreatmentEvolutionService;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.patient.domain.Patient;

/** 1B.1: HTTP, transactions and races on PostgreSQL 16 with actual V001–V013. */
class ClinicalAttentionTraceApiTest extends AppointmentIntegrationTestSupport {
    @Autowired ClinicalService clinical;
    @Autowired TreatmentEvolutionService evolutions;
    @Autowired AppointmentService appointments;
    @Autowired com.dentalclinic.clinical.infrastructure.repository.ClinicalNoteRepository clinicalNoteRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;

    private AuthenticatedUser actor() { return new AuthenticatedUser(dentistUser.getId(), Role.DENTIST); }
    private String clinicalPath() { return "/api/patients/" + patient.getId() + "/clinical"; }
    private long appointment(int hour) throws Exception {
        var response = mockMvc.perform(post("/api/appointments").header("Authorization", bearer(loginToken(owner)))
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(createBody(patient.getId(), dentist.getId(), at(MARTES, hour, 0), at(MARTES, hour + 1, 0))))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(response.getResponse().getContentAsString()).get("id").asLong();
    }
    private long treatment() throws Exception {
        var response = mockMvc.perform(post(clinicalPath() + "/treatments").header("Authorization", bearer(loginToken(dentistUser)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"Tratamiento longitudinal\"}"))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(response.getResponse().getContentAsString()).get("id").asLong();
    }
    private String body(String content, long id, int version) throws Exception {
        return objectMapper.writeValueAsString(Map.of("content", content, "appointmentContext", Map.of("appointmentId", id, "occurrenceVersion", version)));
    }
    private String correctionBody(String content, String reason) throws Exception {
        return objectMapper.writeValueAsString(Map.of("content", content, "correctionReason", reason));
    }
    private long correctNote(User user, long id, long noteId, String content, String reason) throws Exception {
        var response = mockMvc.perform(post(clinicalPath() + "/notes/" + noteId + "/corrections")
                .header("Authorization", bearer(loginToken(user))).contentType(MediaType.APPLICATION_JSON)
                .content(correctionBody(content, reason))).andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(response.getResponse().getContentAsString()).get("id").asLong();
    }
    private org.springframework.test.web.servlet.ResultActions note(User user, long id, int version, String content) throws Exception {
        return mockMvc.perform(post(clinicalPath() + "/notes").header("Authorization", bearer(loginToken(user)))
                .contentType(MediaType.APPLICATION_JSON).content(body(content, id, version)));
    }
    private String trace(long id) { return clinicalPath() + "/appointments/" + id + "/trace"; }
    private long count(String table) { return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class); }

    @Test
    void citasABC_yGenerales_seSeparanConPaginacionYEvolucionLongitudinal() throws Exception {
        long treatment = treatment();
        for (int hour : List.of(9, 11, 13)) {
            long id = appointment(hour);
            note(dentistUser, id, 1, "Nota " + hour).andExpect(status().isCreated())
                    .andExpect(jsonPath("$.appointmentContext.occurrenceVersion").value(1))
                    .andExpect(jsonPath("$.appointmentContext.statusAtRegistration").value("SCHEDULED"));
            mockMvc.perform(post(clinicalPath() + "/treatments/" + treatment + "/evolution")
                    .header("Authorization", bearer(loginToken(dentistUser))).contentType(MediaType.APPLICATION_JSON)
                    .content(body("Procedimiento descrito " + hour, id, 1))).andExpect(status().isCreated());
            for (String section : List.of("NOTES", "EVOLUTIONS")) {
                mockMvc.perform(get(trace(id)).param("occurrenceVersion", "1").param("section", section)
                        .header("Authorization", bearer(loginToken(owner))))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.context.kind").value("FROZEN"))
                        .andExpect(jsonPath("$.results.totalElements").value(1))
                        .andExpect(jsonPath("$.results.content[0].recordId").isNumber())
                        .andExpect(jsonPath("$.availableSections.length()").value(2));
            }
        }
        mockMvc.perform(post(clinicalPath() + "/notes").header("Authorization", bearer(loginToken(dentistUser)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"General\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.appointmentContext").doesNotExist());
        mockMvc.perform(post(clinicalPath() + "/treatments/" + treatment + "/evolution")
                .header("Authorization", bearer(loginToken(owner))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Evolución general\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.appointmentContext").doesNotExist());
        mockMvc.perform(get(clinicalPath() + "/notes").param("generalOnly", "true").header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get(clinicalPath() + "/notes").header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4));
        assertThat(count("appointment_occurrence_snapshot")).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void versionObsoletaRechazada_inclusoConSnapshot_yMismoHorario(boolean existingSnapshot) throws Exception {
        long id = appointment(9);
        if (existingSnapshot) note(dentistUser, id, 1, "Original").andExpect(status().isCreated());
        var before = jdbcTemplate.queryForList("SELECT * FROM appointment_occurrence_snapshot");
        appointments.reschedule(id, new AuthenticatedUser(owner.getId(), Role.OWNER),
                new AppointmentService.RescheduleData(at(MARTES, 9, 0), at(MARTES, 10, 0), 1));
        note(dentistUser, id, 1, "No reasignar").andExpect(status().isConflict());
        assertThat(jdbcTemplate.queryForList("SELECT * FROM appointment_occurrence_snapshot")).isEqualTo(before);
        mockMvc.perform(get(trace(id)).param("occurrenceVersion", "1").param("section", "NOTES")
                .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.context.kind").value(existingSnapshot ? "FROZEN" : "HISTORICAL_UNKNOWN"));
        assertThat(count("clinical_note")).isEqualTo(existingSnapshot ? 1 : 0);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM appointment_occurrence_snapshot")).isEqualTo(before);
    }

    @Test
    void getNoCreaSnapshot_yOrdenEstableYPaginacion() throws Exception {
        long id = appointment(9);
        mockMvc.perform(get(trace(id)).param("occurrenceVersion", "1").param("section", "NOTES")
                .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.context.kind").value("CURRENT_UNFROZEN"));
        assertThat(count("appointment_occurrence_snapshot")).isZero();
        note(owner, id, 1, "A").andExpect(status().isCreated());
        note(dentistUser, id, 1, "B").andExpect(status().isCreated());
        mockMvc.perform(get(trace(id)).param("occurrenceVersion", "1").param("section", "NOTES").param("size", "1").param("page", "1")
                .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.results.totalElements").value(2))
                .andExpect(jsonPath("$.results.content[0].currentEntry.content").value("B"));
        assertThat(count("appointment_occurrence_snapshot")).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = {"CANCELLED", "NO_SHOW"})
    void terminalesRechazanContextuales_peroPermitenGenerales(AppointmentStatus terminal) throws Exception {
        long id = appointment(9);
        note(dentistUser, id, 1, "Previa").andExpect(status().isCreated());
        if (terminal == AppointmentStatus.CANCELLED) appointments.cancel(id, new AuthenticatedUser(owner.getId(), Role.OWNER));
        else {
            TestTime.advance(Duration.between(TestTime.now(), at(MARTES, 9, 0)));
            appointments.markNoShow(id, new AuthenticatedUser(owner.getId(), Role.OWNER));
        }
        note(dentistUser, id, 1, "No registrar").andExpect(status().isConflict());
        long t = treatment();
        mockMvc.perform(post(clinicalPath() + "/treatments/" + t + "/evolution").header("Authorization", bearer(loginToken(owner)))
                .contentType(MediaType.APPLICATION_JSON).content(body("No registrar", id, 1))).andExpect(status().isConflict());
        mockMvc.perform(post(clinicalPath() + "/notes").header("Authorization", bearer(loginToken(owner)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"General válida\"}"))
                .andExpect(status().isCreated());
        assertThat(count("clinical_note")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completedPermitePosteriores_yCompleteNoEscribeClinicaNiSnapshot(boolean hasContextualData) throws Exception {
        long id = appointment(9);
        if (hasContextualData) {
            note(dentistUser, id, 1, "Antes de completar").andExpect(status().isCreated());
            evolutions.append(patient.getId(), treatment(), actor(), "Evolución anterior", new Selection(id, 1));
        }
        var tables = List.of("clinical_record", "clinical_note", "treatment", "treatment_evolution", "treatment_tooth",
                "tooth_record", "tooth_history", "tooth_surface_record", "tooth_surface_history", "appointment_occurrence_snapshot");
        var before = tables.stream().map(table -> jdbcTemplate.queryForList("SELECT * FROM " + table)).toList();
        appointments.complete(id, actor(), 1, java.util.UUID.randomUUID().toString());
        for (int i = 0; i < tables.size(); i++) assertThat(jdbcTemplate.queryForList("SELECT * FROM " + tables.get(i))).isEqualTo(before.get(i));
        note(owner, id, 1, "Información posterior").andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorDentistId").value(ownerDentist.getId()))
                .andExpect(jsonPath("$.appointmentContext.statusAtRegistration").value("COMPLETED"));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"OWNER", "DENTIST"})
    void profesionalSinPerfil409_sinNingunaEscritura(Role role) throws Exception {
        User user = newUser(role);
        long id = appointment(9);
        long t = treatment();
        note(user, id, 1, "Sin autor").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("perfil profesional")));
        mockMvc.perform(post(clinicalPath() + "/treatments/" + t + "/evolution").header("Authorization", bearer(loginToken(user)))
                .contentType(MediaType.APPLICATION_JSON).content(body("Sin autor", id, 1))).andExpect(status().isConflict());
        assertThat(count("clinical_record")).isZero();
        assertThat(count("appointment_occurrence_snapshot")).isZero();
        assertThat(count("treatment_evolution")).isZero();
    }

    @Test
    void permisosRecursosCruzadosYValidaciones() throws Exception {
        long id = appointment(9);
        long t = treatment();
        for (User user : List.of(receptionist, patientUser)) {
            note(user, id, 1, "Restringida").andExpect(status().isForbidden());
            mockMvc.perform(get(trace(id)).param("occurrenceVersion", "1").param("section", "NOTES")
                    .header("Authorization", bearer(loginToken(user)))).andExpect(status().isForbidden());
        }
        mockMvc.perform(get(trace(id)).param("occurrenceVersion", "1").param("section", "NOTES")).andExpect(status().isUnauthorized());
        var other = patientRepository.saveAndFlush(Patient.create("Otro", "Paciente", null, null, null, null));
        mockMvc.perform(post("/api/patients/" + other.getId() + "/clinical/notes")
                .header("Authorization", bearer(loginToken(owner))).contentType(MediaType.APPLICATION_JSON)
                .content(body("Cruzada", id, 1))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/patients/" + other.getId() + "/clinical/appointments/" + id + "/trace")
                .param("occurrenceVersion", "1").param("section", "NOTES")
                .header("Authorization", bearer(loginToken(owner)))).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/patients/" + other.getId() + "/clinical/treatments/" + t + "/evolution")
                .header("Authorization", bearer(loginToken(owner))).contentType(MediaType.APPLICATION_JSON)
                .content(body("Tratamiento cruzado", id, 1))).andExpect(status().isNotFound());
        note(owner, 999999, 1, "Inexistente").andExpect(status().isNotFound());
        mockMvc.perform(post(clinicalPath() + "/notes").header("Authorization", bearer(loginToken(owner)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Incompleto\",\"appointmentContext\":{\"appointmentId\":1}}"))
                .andExpect(status().isBadRequest());
        for (String[] params : List.of(new String[]{"0", "NOTES", "20"}, new String[]{"1", "FUTURE", "20"}, new String[]{"1", "NOTES", "101"})) {
            mockMvc.perform(get(trace(id)).param("occurrenceVersion", params[0]).param("section", params[1]).param("size", params[2])
                    .header("Authorization", bearer(loginToken(owner)))).andExpect(status().isBadRequest());
        }
        assertThat(count("appointment_occurrence_snapshot")).isZero();
    }

    @Test
    void falloDespuesDeCapturarRevierteSnapshotYEntrada() throws Exception {
        long id = appointment(9);
        long t = treatment();
        assertThatThrownBy(() -> evolutions.append(patient.getId(), t, actor(), "x".repeat(4001), new Selection(id, 1)))
                .isInstanceOf(com.dentalclinic.clinical.domain.InvalidTreatmentException.class);
        assertThat(count("appointment_occurrence_snapshot")).isZero();
        assertThat(count("treatment_evolution")).isZero();
    }

    @Test
    void contextoRechazaDecimalesYCadenas_sinTruncarIdentidad() throws Exception {
        long id = appointment(9);
        long t = treatment();
        for (String invalid : List.of(
                "{\"appointmentId\":" + id + ",\"occurrenceVersion\":1.5}",
                "{\"appointmentId\":" + id + ".5,\"occurrenceVersion\":1}",
                "{\"appointmentId\":" + id + ",\"occurrenceVersion\":\"1\"}",
                "{\"appointmentId\":" + id + ",\"occurrenceVersion\":2147483648}")) {
            String input = "{\"content\":\"No truncar identidad\",\"appointmentContext\":" + invalid + "}";
            for (String path : List.of(clinicalPath() + "/notes", clinicalPath() + "/treatments/" + t + "/evolution")) {
                mockMvc.perform(post(path).header("Authorization", bearer(loginToken(owner)))
                        .contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isBadRequest());
            }
        }
        assertThat(count("appointment_occurrence_snapshot")).isZero();
        assertThat(count("clinical_note")).isZero();
        assertThat(count("treatment_evolution")).isZero();
    }

    @Test
    void ensureConcurrenteNoDuplicaExpedienteNiSobrescribeDatos() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Void> save = () -> { barrier.await(10, TimeUnit.SECONDS); clinical.addNote(patient.getId(), actor(), "General concurrente", null); return null; };
            var first = pool.submit(save); var second = pool.submit(save);
            first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
        }
        assertThat(count("clinical_record")).isEqualTo(1);
        assertThat(count("clinical_note")).isEqualTo(2);
        clinical.saveRecord(patient.getId(), new ClinicalService.RecordData("Antecedente", "Alergia", null, null));
        clinical.addNote(patient.getId(), actor(), "No reemplazar", null);
        assertThat(clinical.getRecord(patient.getId()).allergies()).isEqualTo("Alergia");
    }

    @Test
    void entidadJpaLeidaAntesDelLockSeRefresca() throws Exception {
        long id = appointment(9);
        var read = new CountDownLatch(1); var rescheduled = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var stale = pool.submit(() -> new TransactionTemplate(transactionManager).execute(tx -> {
                var old = appointmentRepository.findById(id).orElseThrow();
                assertThat(old.getOccurrenceVersion()).isEqualTo(1);
                read.countDown(); await(rescheduled);
                return clinical.addNote(patient.getId(), actor(), "Obsoleta en caché JPA", new Selection(id, 1));
            }));
            await(read);
            appointments.reschedule(id, new AuthenticatedUser(owner.getId(), Role.OWNER),
                    new AppointmentService.RescheduleData(at(MARTES, 11, 0), at(MARTES, 12, 0), 1));
            rescheduled.countDown();
            assertThatThrownBy(() -> stale.get(20, TimeUnit.SECONDS)).hasRootCauseInstanceOf(com.dentalclinic.scheduling.domain.ScheduleConflictException.class);
        }
        assertThat(count("clinical_note")).isZero();
        assertThat(count("appointment_occurrence_snapshot")).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lockHastaCommit_serializaGuardadoYReprogramacion(boolean saveFirst) throws Exception {
        long id = appointment(9);
        var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> new TransactionTemplate(transactionManager).execute(tx -> {
                if (saveFirst) clinical.addNote(patient.getId(), actor(), "En V1", new Selection(id, 1));
                else appointments.reschedule(id, new AuthenticatedUser(owner.getId(), Role.OWNER),
                        new AppointmentService.RescheduleData(at(MARTES, 11, 0), at(MARTES, 12, 0), 1));
                held.countDown(); await(release); return null;
            }));
            await(held);
            var second = pool.submit(() -> {
                if (saveFirst) return appointments.reschedule(id, new AuthenticatedUser(owner.getId(), Role.OWNER),
                        new AppointmentService.RescheduleData(at(MARTES, 11, 0), at(MARTES, 12, 0), 1));
                return clinical.addNote(patient.getId(), actor(), "No mover a V2", new Selection(id, 1));
            });
            try { awaitBlockedAppointment(); } finally { release.countDown(); }
            first.get(20, TimeUnit.SECONDS);
            if (saveFirst) second.get(20, TimeUnit.SECONDS);
            else assertThatThrownBy(() -> second.get(20, TimeUnit.SECONDS)).hasRootCauseInstanceOf(com.dentalclinic.scheduling.domain.ScheduleConflictException.class);
        } finally { release.countDown(); }
        assertThat(appointmentRepository.findById(id).orElseThrow().getOccurrenceVersion()).isEqualTo(2);
        assertThat(count("clinical_note")).isEqualTo(saveFirst ? 1 : 0);
        assertThat(count("appointment_occurrence_snapshot")).isEqualTo(saveFirst ? 1 : 0);
        if (saveFirst) assertThat(jdbcTemplate.queryForObject("SELECT scheduled_start_at FROM appointment_occurrence_snapshot", java.sql.Timestamp.class).toInstant()).isEqualTo(at(MARTES, 9, 0));
    }

    @Test
    void evolucionVersionObsoleta409_yNuevaOcurrenciaConservaSnapshotAnterior() throws Exception {
        long id = appointment(9);
        long t = treatment();
        mockMvc.perform(post(clinicalPath() + "/treatments/" + t + "/evolution")
                        .header("Authorization", bearer(loginToken(dentistUser))).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Evolución de la ocurrencia 1", id, 1)))
                .andExpect(status().isCreated());
        var v1Snapshot = jdbcTemplate.queryForList("SELECT * FROM appointment_occurrence_snapshot WHERE occurrence_version = 1");
        assertThat(v1Snapshot).hasSize(1);
        appointments.reschedule(id, new AuthenticatedUser(owner.getId(), Role.OWNER),
                new AppointmentService.RescheduleData(at(MARTES, 9, 0), at(MARTES, 10, 0), 1));
        assertThat(appointmentRepository.findById(id).orElseThrow().getOccurrenceVersion()).isEqualTo(2);
        mockMvc.perform(post(clinicalPath() + "/treatments/" + t + "/evolution")
                        .header("Authorization", bearer(loginToken(dentistUser))).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Evolución de ocurrencia obsoleta", id, 1)))
                .andExpect(status().isConflict());
        assertThat(count("treatment_evolution")).isEqualTo(1);
        assertThat(count("appointment_occurrence_snapshot")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM appointment_occurrence_snapshot WHERE occurrence_version = 1")).isEqualTo(v1Snapshot);
        mockMvc.perform(post(clinicalPath() + "/treatments/" + t + "/evolution")
                        .header("Authorization", bearer(loginToken(dentistUser))).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Evolución de la ocurrencia 2", id, 2)))
                .andExpect(status().isCreated());
        assertThat(count("appointment_occurrence_snapshot")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM appointment_occurrence_snapshot WHERE occurrence_version = 1")).isEqualTo(v1Snapshot);
    }

    @Test
    void correccionHeredaElContextoDelOriginal_yLaTrazaMuestraLaCadenaContandoRaices() throws Exception {
        long id = appointment(9);
        var created = note(dentistUser, id, 1, "Texto original").andExpect(status().isCreated()).andReturn();
        long noteId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        var snapshotBefore = jdbcTemplate.queryForList("SELECT * FROM appointment_occurrence_snapshot");

        mockMvc.perform(post(clinicalPath() + "/notes/" + noteId + "/corrections")
                        .header("Authorization", bearer(loginToken(owner))).contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody("Texto corregido", "Aclaracion")))
                .andExpect(status().isCreated())
                // La correccion hereda el contexto historico; no reasigna cita ni crea snapshot.
                .andExpect(jsonPath("$.correctsRecordId").value(noteId))
                .andExpect(jsonPath("$.appointmentContext.appointmentId").value(id))
                .andExpect(jsonPath("$.appointmentContext.occurrenceVersion").value(1))
                .andExpect(jsonPath("$.appointmentContext.statusAtRegistration").value("SCHEDULED"));
        assertThat(jdbcTemplate.queryForList("SELECT * FROM appointment_occurrence_snapshot")).isEqualTo(snapshotBefore);

        mockMvc.perform(get(trace(id)).param("occurrenceVersion", "1").param("section", "NOTES")
                        .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk())
                // Se cuenta por registro original (raiz), no por correcciones.
                .andExpect(jsonPath("$.results.totalElements").value(1))
                .andExpect(jsonPath("$.results.content[0].originalEntry.content").value("Texto original"))
                .andExpect(jsonPath("$.results.content[0].originalEntry.correctsRecordId").doesNotExist())
                .andExpect(jsonPath("$.results.content[0].corrections.length()").value(1))
                .andExpect(jsonPath("$.results.content[0].corrections[0].content").value("Texto corregido"))
                .andExpect(jsonPath("$.results.content[0].corrections[0].correctionReason").value("Aclaracion"))
                .andExpect(jsonPath("$.results.content[0].currentEntry.content").value("Texto corregido"));
        assertThat(count("clinical_note")).isEqualTo(2);
        assertThat(count("appointment_occurrence_snapshot")).isEqualTo(1);
    }

    @Test
    void correccionesConcurrentesDelMismoOriginalSeConservanTodas() throws Exception {
        long id = appointment(9);
        var created = note(dentistUser, id, 1, "Original").andExpect(status().isCreated()).andReturn();
        long noteId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Void> correct = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                clinical.correct(patient.getId(), noteId, actor(), "Correccion concurrente", "Motivo concurrente");
                return null;
            };
            var first = pool.submit(correct); var second = pool.submit(correct);
            first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
        }
        // La raiz y las dos correcciones concurrentes se conservan: no hay sobrescritura silenciosa.
        assertThat(count("clinical_note")).isEqualTo(3);
        mockMvc.perform(get(trace(id)).param("occurrenceVersion", "1").param("section", "NOTES")
                        .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.totalElements").value(1))
                .andExpect(jsonPath("$.results.content[0].corrections.length()").value(2))
                // Vigente = ultima por (createdAt, id): deterministicamente la de mayor id.
                .andExpect(jsonPath("$.results.content[0].currentEntry.id").value(
                        clinicalNoteRepository.findByCorrectsNoteIdInOrderByCreatedAtAscIdAsc(List.of(noteId))
                                .get(1).getId().intValue()));
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("Barrier timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = {"CANCELLED", "NO_SHOW", "COMPLETED"})
    void estadoConcurrenteSeValidaBajoElMismoLock(AppointmentStatus terminal) throws Exception {
        long id = appointment(9);
        TestTime.advance(Duration.between(TestTime.now(), at(MARTES, 9, 0)));
        var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var transition = pool.submit(() -> new TransactionTemplate(transactionManager).execute(tx -> {
                var admin = new AuthenticatedUser(owner.getId(), Role.OWNER);
                switch (terminal) {
                    case CANCELLED -> appointments.cancel(id, admin);
                    case NO_SHOW -> appointments.markNoShow(id, admin);
                    case COMPLETED -> appointments.complete(id, actor(), 1, java.util.UUID.randomUUID().toString());
                    default -> throw new AssertionError();
                }
                held.countDown(); await(release); return null;
            }));
            await(held);
            var save = pool.submit(() -> clinical.addNote(patient.getId(), actor(), "Escritura concurrente", new Selection(id, 1)));
            try { awaitBlockedAppointment(); } finally { release.countDown(); }
            transition.get(20, TimeUnit.SECONDS);
            if (terminal == AppointmentStatus.COMPLETED) {
                assertThat(save.get(20, TimeUnit.SECONDS).appointmentContext().getStatusAtRegistration()).isEqualTo(terminal);
            } else {
                assertThatThrownBy(() -> save.get(20, TimeUnit.SECONDS)).hasRootCauseInstanceOf(com.dentalclinic.scheduling.domain.ScheduleConflictException.class);
                assertThat(count("appointment_occurrence_snapshot")).isZero();
                assertThat(count("clinical_note")).isZero();
            }
        } finally { release.countDown(); }
    }
    private void awaitBlockedAppointment() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long blocked = jdbcTemplate.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE '%appointment%'", Long.class);
            if (blocked > 0) return;
            Thread.sleep(25);
        }
        throw new AssertionError("No PostgreSQL appointment lock wait observed");
    }
}

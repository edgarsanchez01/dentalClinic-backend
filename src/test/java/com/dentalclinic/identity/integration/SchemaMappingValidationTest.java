package com.dentalclinic.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * El mapeo JPA de las Fases 2, 3, 4, 5 y 6 es <strong>validado</strong> contra el esquema SQL real
 * ({@code V001} a {@code V006}, incluidas las tablas de citas y comunicaciones).
 *
 * <p>En produccion {@code ddl-auto=none} (Architecture.md 12.4) y el esquema se aplica a mano, asi
 * que la coherencia entre entidades y DDL no puede improvisarse. Este test aplica el DDL de
 * migracion sobre una base efimera dedicada, levanta el contexto con {@code ddl-auto=validate} y
 * comprueba invariantes del esquema.
 *
 * <p>Usa una base <strong>dedicada</strong> ({@code dentalclinic_validate}) distinta de la del perfil
 * test, para que los dos contextos convivan en la misma JVM sin chocar.
 *
 * <p>Nota: al validar Hibernate <strong>todo</strong> el modelo, el DDL aplicado debe incluir todas
 * las fases implementadas. Por eso este test sustituye al antiguo limite en V003.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:16:///dentalclinic_validate",
        "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=file:../db/schema/V001__baseline.sql,"
                + "file:../db/schema/V002__identity_access.sql,"
                + "file:../db/schema/V003__clinic_onboarding.sql,"
                + "file:../db/schema/V004__patients_clinical.sql,"
                + "file:../db/schema/V005__scheduling.sql,"
                + "file:../db/schema/V006__appointments.sql"
})
class SchemaMappingValidationTest {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void elMapeoValidaContraElDdlReal() {
        assertThat(dataSource).isNotNull();
    }

    @Test
    void existenLasTablasDeLasFasesImplementadas() {
        assertThat(tableNames())
                .contains("users", "invitations", "password_reset_tokens", "refresh_sessions",
                        "installation_state", "clinic", "dentist", "patient", "clinical_record",
                        "clinical_note", "clinic_schedule", "clinic_break", "clinic_schedule_exception",
                        "dentist_schedule", "dentist_break", "dentist_schedule_exception", "dentist_time_off",
                        "appointment", "appointment_event", "communication", "notification",
                        "communication_intention", "http_idempotency");
    }

    @Test
    void noSeAdelantanTablasDeFasesPosteriores() {
        assertThat(tableNames())
                .doesNotContain("treatments", "odontogram", "payments", "services");
    }

    @Test
    void installationStateEsFilaUnica() {
        assertThat(constraintDef("installation_state", "installation_state_singleton_check"))
                .contains("id = 1");
        assertThat(indexNames("installation_state")).contains("installation_state_pkey");
    }

    @Test
    void clinicTieneIndiceSingleton() {
        assertThat(indexNames("clinic")).contains("clinic_singleton_idx");
    }

    @Test
    void dentistUserIdEsUnico() {
        assertThat(constraintDef("dentist", "dentist_user_id_key")).contains("UNIQUE");
    }

    @Test
    void clinicValidaColorDeMarcaYRedesJsonb() {
        assertThat(constraintDef("clinic", "clinic_brand_color_check")).contains("^#[0-9A-Fa-f]{6}$");
        assertThat(constraintDef("clinic", "clinic_social_networks_check"))
                .contains("jsonb_typeof(social_networks)");
    }

    @Test
    void patientUserIdEsNullableYUnico() {
        Boolean nullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable = 'YES' FROM information_schema.columns "
                        + "WHERE table_name = 'patient' AND column_name = 'user_id'",
                Boolean.class);
        assertThat(nullable).as("P-08: un paciente puede no tener cuenta").isTrue();
        assertThat(constraintDef("patient", "patient_user_id_key")).contains("UNIQUE");
    }

    @Test
    void patientEmailNoEsUnico() {
        // RF-PA / AD-20: Patient.email es administrativo, opcional y NO unico. Los UNIQUE de
        // `patient` son user_id (V004) y el compuesto (user_id, id) que anade V006 (A-10/O-1)
        // y habilita la FK compuesta de forma de `communication` -> exactamente 2.
        Integer uniqueConstraints = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint "
                        + "WHERE conrelid = 'patient'::regclass AND contype = 'u'",
                Integer.class);
        assertThat(uniqueConstraints).isEqualTo(2);
        assertThat(constraintDef("patient", "patient_user_id_id_key")).contains("UNIQUE (user_id, id)");
    }

    @Test
    void clinicalRecordEsUnoAUnoConPatient() {
        assertThat(constraintDef("clinical_record", "clinical_record_patient_id_key")).contains("UNIQUE");
        assertThat(columnNullable("clinical_record", "patient_id")).isFalse();
    }

    @Test
    void clinicalNoteEsAppendOnlyYReferenciaAlAutorDentista() {
        assertThat(constraintDef("clinical_note", "clinical_note_author_fkey"))
                .contains("REFERENCES dentist(id)");
        assertThat(constraintDef("clinical_note", "clinical_note_content_not_blank"))
                .contains("btrim(content)");
        assertThat(columnNames("clinical_note"))
                .as("RF-CX-04: las notas son inmutables: no hay updated_at")
                .doesNotContain("updated_at");
    }

    @Test
    void bookingPolicyViveEnClinicConSusChecks() {
        assertThat(columnNames("clinic")).contains("max_advance_days", "min_booking_lead_time_hours",
                "cancellation_lead_time_hours");
        assertThat(constraintDef("clinic", "clinic_max_advance_days_check")).contains("max_advance_days >= 0");
        assertThat(constraintDef("clinic", "clinic_min_booking_lead_time_hours_check"))
                .contains("min_booking_lead_time_hours >= 0");
        assertThat(constraintDef("clinic", "clinic_cancellation_lead_time_hours_check"))
                .contains("cancellation_lead_time_hours >= 0");
    }

    @Test
    void schedulesTienenUniquePorDiaYCheckDeDia() {
        assertThat(constraintDef("clinic_schedule", "clinic_schedule_clinic_day_key")).contains("UNIQUE");
        assertThat(constraintDef("clinic_schedule", "clinic_schedule_day_of_week_check"))
                .contains("day_of_week >= 1");
        assertThat(constraintDef("dentist_schedule", "dentist_schedule_dentist_day_key")).contains("UNIQUE");
        assertThat(constraintDef("clinic_schedule_exception", "clinic_schedule_exception_clinic_date_key"))
                .contains("UNIQUE");
        assertThat(constraintDef("dentist_schedule_exception", "dentist_schedule_exception_dentist_date_key"))
                .contains("UNIQUE");
    }

    @Test
    void breaksTienenExclusionDeSolapamiento() {
        assertThat(constraintDef("clinic_break", "clinic_break_no_overlap")).contains("EXCLUDE");
        assertThat(constraintDef("dentist_break", "dentist_break_no_overlap")).contains("EXCLUDE");
    }

    @Test
    void dentistTimeOffExigeParcialDeUnSoloDia() {
        assertThat(constraintDef("dentist_time_off", "dentist_time_off_hours_check"))
                .contains("start_date = end_date");
    }

    @Test
    void breaksBloqueanSolapamientoYPermitenAdyacencia() {
        ClinicRef clinic = clinicIdOrCreate();
        try {
            Long clinicId = clinic.id();
            jdbcTemplate.update("INSERT INTO clinic_break (clinic_id, day_of_week, start_time, end_time) "
                    + "VALUES (?, 1, TIME '10:00', TIME '10:30')", clinicId);
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "INSERT INTO clinic_break (clinic_id, day_of_week, start_time, end_time) "
                            + "VALUES (?, 1, TIME '10:15', TIME '10:45')", clinicId))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            jdbcTemplate.update("INSERT INTO clinic_break (clinic_id, day_of_week, start_time, end_time) "
                    + "VALUES (?, 1, TIME '10:30', TIME '11:00')", clinicId);
        } finally {
            clinic.cleanup(jdbcTemplate);
        }
    }

    @Test
    void clinicBreakExclusionEsPorClinicaDiaEIntervalo() {
        String def = constraintDef("clinic_break", "clinic_break_no_overlap");
        assertThat(def).contains("clinic_id").contains("day_of_week").contains("&&");
    }

    @Test
    void dentistBreakBloqueaSolapamientoDentroDelMismoDentistaYPermiteAdyacencia() {
        ClinicRef clinic = clinicIdOrCreate();
        Long u1 = null;
        Long u2 = null;
        try {
            Long clinicId = clinic.id();
            u1 = jdbcTemplate.queryForObject(
                    "INSERT INTO users (email, password_hash, role, active, created_at) "
                            + "VALUES ('audit-a@c.com', 'x', 'DENTIST', TRUE, now()) RETURNING id",
                    Long.class);
            u2 = jdbcTemplate.queryForObject(
                    "INSERT INTO users (email, password_hash, role, active, created_at) "
                            + "VALUES ('audit-b@c.com', 'x', 'DENTIST', TRUE, now()) RETURNING id",
                    Long.class);
            Long d1 = jdbcTemplate.queryForObject(
                    "INSERT INTO dentist (user_id, first_name, last_name, phone, created_at) "
                            + "VALUES (?, 'A', 'Uno', '600000000', now()) RETURNING id", Long.class, u1);
            Long d2 = jdbcTemplate.queryForObject(
                    "INSERT INTO dentist (user_id, first_name, last_name, phone, created_at) "
                            + "VALUES (?, 'B', 'Dos', '600000001', now()) RETURNING id", Long.class, u2);

            jdbcTemplate.update("INSERT INTO dentist_break (dentist_id, day_of_week, start_time, end_time) "
                    + "VALUES (?, 1, TIME '10:00', TIME '10:30')", d1);
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "INSERT INTO dentist_break (dentist_id, day_of_week, start_time, end_time) "
                            + "VALUES (?, 1, TIME '10:15', TIME '10:45')", d1))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            // Adyacente permitido.
            jdbcTemplate.update("INSERT INTO dentist_break (dentist_id, day_of_week, start_time, end_time) "
                    + "VALUES (?, 1, TIME '10:30', TIME '11:00')", d1);
            // Otro dentista puede tener el mismo intervalo sin conflicto entre ellos.
            jdbcTemplate.update("INSERT INTO dentist_break (dentist_id, day_of_week, start_time, end_time) "
                    + "VALUES (?, 1, TIME '10:00', TIME '10:30')", d2);
            assertThat(clinicId).isNotNull();
        } finally {
            clinic.cleanup(jdbcTemplate);
            if (u1 != null) {
                jdbcTemplate.update("DELETE FROM dentist WHERE user_id = ?", u1);
            }
            if (u2 != null) {
                jdbcTemplate.update("DELETE FROM dentist WHERE user_id = ?", u2);
            }
            jdbcTemplate.update("DELETE FROM users WHERE email IN ('audit-a@c.com', 'audit-b@c.com')");
        }
    }

    @Test
    void citasSoloOcupanSlotCuandoEstanScheduled() {
        // A-14: mismo predicado EXCLUDE que el servicio de disponibilidad (Architecture.md 10.3/10.4).
        // pg_get_constraintdef normaliza la expresion (::text, parentesis extra), asi que se busca
        // lo esencial: EXCLUDE gist sobre dentista e intervalo semiabierto condicionado a SCHEDULED.
        String def = constraintDef("appointment", "appointment_no_overlap");
        assertThat(def).contains("EXCLUDE").contains("dentist_id").contains("tstzrange")
                .contains("&&").contains("SCHEDULED");
    }

    @Test
    void appointmentValidaRangoDuracionYCompletado() {
        assertThat(constraintDef("appointment", "appointment_range_check")).contains("end_at > start_at");
        assertThat(constraintDef("appointment", "appointment_duration_check")).contains("duration_minutes > 0");
        assertThat(constraintDef("appointment", "appointment_version_check")).contains("occurrence_version >= 1");
        assertThat(constraintDef("appointment", "appointment_completed_check"))
                .contains("completed_at IS NOT NULL");
    }

    @Test
    void appointmentEventCierraConjuntoYRegistraUnaSolaLlegadaEInicio() {
        assertThat(constraintDef("appointment_event", "appointment_event_type_check"))
                .contains("APPOINTMENT_CREATED").contains("REMINDER_SENT").contains("FEEDBACK_REQUESTED");
        assertThat(indexNames("appointment_event"))
                .contains("appointment_event_one_arrival_idx", "appointment_event_one_start_idx");
    }

    @Test
    void communicationOutboxSoloEmailConFormaClaveYFkCompuesta() {
        assertThat(constraintDef("communication", "communication_channel_check"))
                .contains("EMAIL").doesNotContain("IN_APP");
        assertThat(constraintDef("communication", "communication_recipient_check"))
                .contains("recipient_kind").contains("recipient_user_id").contains("recipient_patient_id");
        assertThat(constraintDef("communication", "communication_idempotency_key"))
                .contains("UNIQUE");
        assertThat(constraintDef("communication", "communication_recipient_shape_fkey"))
                .contains("REFERENCES patient").contains("user_id").contains("id");
        assertThat(columnNullable("communication", "address")).isFalse();
    }

    @Test
    void communicationIntentionCumpleLasInvariantesA06() {
        assertThat(constraintDef("communication_intention", "ci_event_check"))
                .contains("CREATED", "RESCHEDULED", "CANCELLED", "REMINDER");
        assertThat(constraintDef("communication_intention", "ci_uniq")).contains("UNIQUE");
        assertThat(constraintDef("communication_intention", "ci_deliverable_check"))
                .contains("DELIVERABLE");
        assertThat(constraintDef("communication_intention", "ci_absent_check"))
                .contains("NO_CONTACT_CHANNEL");
        assertThat(constraintDef("communication_intention", "ci_comm_channel_check"))
                .contains("communication_id IS NULL").contains("EMAIL");
        assertThat(constraintDef("communication_intention", "ci_notif_channel_check"))
                .contains("notification_id IS NULL").contains("IN_APP");
    }

    @Test
    void httpIdempotencyEsUnicaPorActorOperacionYValidaEstado() {
        assertThat(constraintDef("http_idempotency", "http_idempotency_actor_operation_key"))
                .contains("UNIQUE (actor_user_id, operation, idempotency_key)");
        assertThat(constraintDef("http_idempotency", "http_idempotency_response_status_check"))
                .contains("response_status >= 200").contains("599");
    }

    @Test
    void citasSolapadasDelMismoDentistaRechazadasYAdyacenciaYTerminalPermitidas() {
        Long uDentist = null;
        Long uPatient = null;
        Long dentistId = null;
        Long patientId = null;
        try {
            uDentist = jdbcTemplate.queryForObject(
                    "INSERT INTO users (email, password_hash, role, active, created_at) "
                            + "VALUES ('audit-ap-d@c.com', 'x', 'DENTIST', TRUE, now()) RETURNING id",
                    Long.class);
            uPatient = jdbcTemplate.queryForObject(
                    "INSERT INTO users (email, password_hash, role, active, created_at) "
                            + "VALUES ('audit-ap-p@c.com', 'x', 'PATIENT', TRUE, now()) RETURNING id",
                    Long.class);
            dentistId = jdbcTemplate.queryForObject(
                    "INSERT INTO dentist (user_id, first_name, last_name, phone, created_at) "
                            + "VALUES (?, 'Citas', 'Dental', '600000000', now()) RETURNING id",
                    Long.class, uDentist);
            patientId = jdbcTemplate.queryForObject(
                    "INSERT INTO patient (user_id, first_name, last_name, phone, created_at) "
                            + "VALUES (?, 'Paciente', 'Cita', '600000001', now()) RETURNING id",
                    Long.class, uPatient);

            insertAppointment(patientId, dentistId, uDentist, "2026-11-02T09:00:00Z",
                    "2026-11-02T10:00:00Z");
            // Solapamiento del mismo dentista, ambos SCHEDULED -> EXCLUDE (RF-AP-16).
            final Long overlapDentist = dentistId;
            final Long overlapPatient = patientId;
            final Long overlapActor = uDentist;
            assertThatThrownBy(() -> insertAppointment(overlapPatient, overlapDentist, overlapActor,
                    "2026-11-02T09:30:00Z", "2026-11-02T10:30:00Z"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            // Adyacencia [10:00, 11:00) permitida (intervalo semiabierto, RF-AV-06).
            insertAppointment(patientId, dentistId, uDentist, "2026-11-02T10:00:00Z",
                    "2026-11-02T11:00:00Z");
            // El mismo horario, una vez en estado terminal, deja de ocupar slot (A-14): ahora
            // [09:15, 09:45) solo solapa con la cita ya COMPLETED, no con la SCHEDULED [10:00, 11:00).
            jdbcTemplate.update("UPDATE appointment SET status = 'COMPLETED', completed_at = now() "
                    + "WHERE dentist_id = ? AND start_at = '2026-11-02T09:00:00Z'", dentistId);
            insertAppointment(patientId, dentistId, uDentist, "2026-11-02T09:15:00Z",
                    "2026-11-02T09:45:00Z");
        } finally {
            if (dentistId != null) {
                jdbcTemplate.update("DELETE FROM appointment WHERE dentist_id = ?", dentistId);
            }
            if (patientId != null) {
                jdbcTemplate.update("DELETE FROM patient WHERE id = ?", patientId);
            }
            if (dentistId != null) {
                jdbcTemplate.update("DELETE FROM dentist WHERE id = ?", dentistId);
            }
            if (uDentist != null) {
                jdbcTemplate.update("DELETE FROM users WHERE id = ?", uDentist);
            }
            if (uPatient != null) {
                jdbcTemplate.update("DELETE FROM users WHERE id = ?", uPatient);
            }
        }
    }

    private void insertAppointment(Long patientId, Long dentistId, Long actorUserId, String startAt,
                                   String endAt) {
        int durationMinutes = (int) Duration.between(Instant.parse(startAt), Instant.parse(endAt))
                .toMinutes();
        jdbcTemplate.update("INSERT INTO appointment (patient_id, dentist_id, created_by_user_id, "
                        + "start_at, end_at, duration_minutes, status, occurrence_version) "
                        + "VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz, ?, 'SCHEDULED', 1)",
                patientId, dentistId, actorUserId, startAt, endAt, durationMinutes);
    }

    /** Referencia a la fila singleton {@code clinic}; borra las franquicias y, si la creó, la clínica. */
    private record ClinicRef(Long id, boolean created) {
        void cleanup(JdbcTemplate jdbc) {
            jdbc.update("DELETE FROM clinic_break WHERE clinic_id = ?", id);
            jdbc.update("DELETE FROM dentist_break");
            if (created) {
                jdbc.update("DELETE FROM clinic WHERE id = ?", id);
            }
        }
    }

    private ClinicRef clinicIdOrCreate() {
        java.util.List<Long> ids = jdbcTemplate.queryForList("SELECT id FROM clinic LIMIT 1", Long.class);
        if (!ids.isEmpty()) {
            return new ClinicRef(ids.get(0), false);
        }
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO clinic (name, timezone) VALUES ('Clínica', 'UTC') RETURNING id", Long.class);
        return new ClinicRef(id, true);
    }

    private java.util.List<String> tableNames() {
        return jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' "
                        + "AND table_type = 'BASE TABLE'",
                String.class);
    }

    private java.util.List<String> columnNames(String table) {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ?",
                String.class, table);
    }

    private boolean columnNullable(String table, String column) {
        Boolean nullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable = 'YES' FROM information_schema.columns "
                        + "WHERE table_name = ? AND column_name = ?",
                Boolean.class, table, column);
        return Boolean.TRUE.equals(nullable);
    }

    private java.util.List<String> indexNames(String table) {
        return jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = ?", String.class, table);
    }

    private String constraintDef(String table, String constraint) {
        return jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conrelid = ?::regclass AND conname = ?",
                String.class, table, constraint);
    }
}
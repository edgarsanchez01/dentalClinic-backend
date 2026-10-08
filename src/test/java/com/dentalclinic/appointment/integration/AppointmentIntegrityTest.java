package com.dentalclinic.appointment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;

import com.dentalclinic.identity.domain.User;

/**
 * F6.D · Invariantes que garantiza el motor y no la aplicación: FK de las citas, índice parcial de
 * llegada, EXCLUDE anti-doble-booking, claves de idempotencia e integridad de las intenciones.
 *
 * <p>Estas pruebas escriben directamente en la base precisamente para demostrar que la protección
 * no depende de la capa de servicio (A-06, A-09, A-14, A-16).
 */
class AppointmentIntegrityTest extends AppointmentIntegrationTestSupport {

    private static final AtomicInteger KEYS = new AtomicInteger();

    @Test
    void lasCitasNoSeBorranSiAlgoSigueApuntandoAEllas() throws Exception {
        Long id = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));

        assertThatThrownBy(() -> appointmentRepository.deleteById(id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("appointment_event_appointment_fkey");

        assertThatThrownBy(() -> patientRepository.deleteById(patient.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("appointment_patient_fkey");

        assertThat(appointmentRepository.findById(id)).isPresent();
        assertThat(patientRepository.findById(patient.getId())).isPresent();
    }

    /** A-09/D-F6-24.b: el índice parcial UNIQUE es el que hace imposible la doble llegada. */
    @Test
    void elIndiceDeLlegadaImpideLaSegundaFilaAunqueSeEscribaDirectamente() throws Exception {
        Long id = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));

        jdbcTemplate.update("INSERT INTO appointment_event "
                        + "(appointment_id, event_type, actor_user_id, occurrence_version) "
                        + "VALUES (?, 'PATIENT_ARRIVED', ?, 1)", id, dentistUser.getId());

        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO appointment_event "
                        + "(appointment_id, event_type, actor_user_id, occurrence_version) "
                        + "VALUES (?, 'PATIENT_ARRIVED', ?, 1)", id, dentistUser.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("appointment_event_one_arrival_idx");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM appointment_event WHERE appointment_id = ? "
                        + "AND event_type = 'PATIENT_ARRIVED'", Long.class, id)).isEqualTo(1);
    }

    /** A-14: la EXCLUDE del motor protege el slot aunque se escriba fuera de la aplicación. */
    @Test
    void laConstraintExcludeImpideElSolapeTambienSinLaAplicacion() throws Exception {
        createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO appointment (patient_id, dentist_id, created_by_user_id, "
                        + "start_at, end_at, duration_minutes) VALUES (?, ?, ?, ?, ?, 60)",
                patient.getId(), dentist.getId(), owner.getId(),
                ts(at(MARTES, 9, 30)), ts(at(MARTES, 10, 30))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("appointment_no_overlap");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM appointment WHERE dentist_id = ?", Long.class,
                dentist.getId())).isEqualTo(1);

        jdbcTemplate.update(
                "INSERT INTO appointment (patient_id, dentist_id, created_by_user_id, "
                        + "start_at, end_at, duration_minutes) VALUES (?, ?, ?, ?, ?, 60)",
                patient.getId(), dentist.getId(), owner.getId(),
                ts(at(MARTES, 10, 0)), ts(at(MARTES, 11, 0)));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM appointment WHERE dentist_id = ?", Long.class,
                dentist.getId())).isEqualTo(2);
    }

    /** A-06: una intención es única por versión/canal/rol y una DELIVERABLE enlaza de verdad. */
    @Test
    void lasIntencionesNoSeDuplicanYUnaDeliverableExigeSuEnlace() throws Exception {
        Long id = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO communication_intention (appointment_id, event_type, "
                        + "occurrence_version, channel, required_role, party_kind, party_id, "
                        + "recipient_kind, resolution) VALUES (?, 'CREATED', 1, 'EMAIL', "
                        + "'PATIENT', 'PATIENT', ?, 'PATIENT', 'NO_CONTACT_CHANNEL')",
                id, patient.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("ci_uniq");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO communication_intention (appointment_id, event_type, "
                        + "occurrence_version, channel, required_role, party_kind, party_id, "
                        + "recipient_kind, resolution) VALUES (?, 'CREATED', 1, 'EMAIL', "
                        + "'DENTIST', 'DENTIST', ?, 'USER', 'DELIVERABLE')",
                id, dentist.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("ci_deliverable_check");
    }

    @Test
    void lasClavesDeIdempotenciaSonUnicasPorActorOperacionYClave() throws Exception {
        Long id = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));
        String key = jdbcTemplate.queryForObject(
                "SELECT idempotency_key FROM http_idempotency WHERE operation = 'CREATE_APPOINTMENT'",
                String.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO http_idempotency (actor_user_id, operation, idempotency_key, "
                        + "request_hash, response_status, response_body) VALUES (?, "
                        + "'CREATE_APPOINTMENT', ?, ?, 201, '{}'::jsonb)",
                owner.getId(), key, "a".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause().hasMessageContaining("http_idempotency_actor_operation_key");

        assertThat(countRows("http_idempotency")).isEqualTo(1);
        assertThat(appointmentRepository.findById(id)).isPresent();
    }

    // ------------------------------------------------------------- helpers

    private Long createAndId(User actor, Long patientId, Long dentistId, Instant start,
            Instant end) throws Exception {
        var result = mockMvc.perform(post("/api/appointments")
                        .header("Authorization", bearer(loginToken(actor)))
                        .header("Idempotency-Key", nextKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(patientId, dentistId, start, end)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private static java.sql.Timestamp ts(Instant instant) {
        return java.sql.Timestamp.from(instant);
    }

    private static String nextKey() {
        return "k-" + KEYS.incrementAndGet() + "-" + UUID.randomUUID();
    }
}

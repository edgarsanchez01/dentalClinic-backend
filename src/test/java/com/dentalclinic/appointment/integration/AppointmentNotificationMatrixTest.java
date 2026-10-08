package com.dentalclinic.appointment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.dentalclinic.identity.domain.User;
import com.dentalclinic.patient.domain.Patient;

/**
 * F6.D · Materialización de la matriz §14.6 y de la resolución de destinatario A-03/D-F6-12.
 */
class AppointmentNotificationMatrixTest extends AppointmentIntegrationTestSupport {

    private static final AtomicInteger KEYS = new AtomicInteger();

    /** D-F6-01: sin canal no se inventa destinatario y la cita se crea igual. */
    @Test
    void sinCanalDeContactoSeRegistraNoContactChannelYLaCitaSeCrea() throws Exception {
        Patient sinCanal = seedPatientWithoutChannel();
        Long id = createAndId(owner, sinCanal.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));

        assertThat(countRows("appointment")).isEqualTo(1);
        assertThat(countRows("communication")).isZero();
        assertThat(countRows("notification")).isEqualTo(1);
        assertThat(countRows("communication_intention")).isEqualTo(3);

        List<String> resoluciones = jdbcTemplate.queryForList(
                "SELECT resolution FROM communication_intention WHERE appointment_id = ? ORDER BY id",
                String.class, id);
        assertThat(resoluciones).containsExactly("NO_CONTACT_CHANNEL", "NO_CONTACT_CHANNEL",
                "DELIVERABLE");

        Map<String, Object> email = jdbcTemplate.queryForMap(
                "SELECT address, communication_id, notification_id FROM communication_intention "
                        + "WHERE appointment_id = ? AND channel = 'EMAIL'", id);
        assertThat(email.get("address")).isNull();
        assertThat(email.get("communication_id")).isNull();
        assertThat(email.get("notification_id")).isNull();

        assertThat(jdbcTemplate.queryForList(
                "SELECT user_id FROM notification WHERE appointment_id = ?", Long.class, id))
                .containsExactly(dentistUser.getId());
    }

    /** A-03: el correo del {@code User} manda sobre el del {@code Patient}; nada se deduce. */
    @Test
    void elDestinatarioEsElUsuarioCuandoTieneCuenta() throws Exception {
        Long id = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT recipient_kind, recipient_user_id, recipient_patient_id, address, "
                        + "idempotency_key FROM communication WHERE appointment_id = ?", id);
        assertThat(row.get("recipient_kind")).isEqualTo("USER");
        assertThat(((Number) row.get("recipient_user_id")).longValue())
                .isEqualTo(patientUser.getId());
        assertThat(row.get("recipient_patient_id")).isNull();
        assertThat(row.get("address")).isEqualTo(patientUser.getEmail());
        assertThat(row.get("idempotency_key")).isEqualTo(
                "APPOINTMENT:" + id + ":CREATED:EMAIL:U" + patientUser.getId());
    }

    /** A-03: sin cuenta, el destinatario es la fila {@code patient} (nunca un correo inventado). */
    @Test
    void sinCuentaElDestinatarioEsLaFilaDelPaciente() throws Exception {
        Patient sinCuenta = patientRepository.saveAndFlush(
                Patient.create("Solo", "Correo", "solo.correo@dc.test", null, null, null));
        Long id = createAndId(owner, sinCuenta.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT recipient_kind, recipient_user_id, recipient_patient_id, address, "
                        + "idempotency_key FROM communication WHERE appointment_id = ?", id);
        assertThat(row.get("recipient_kind")).isEqualTo("PATIENT");
        assertThat(row.get("recipient_user_id")).isNull();
        assertThat(((Number) row.get("recipient_patient_id")).longValue()).isEqualTo(sinCuenta.getId());
        assertThat(row.get("address")).isEqualTo("solo.correo@dc.test");
        assertThat(row.get("idempotency_key")).isEqualTo(
                "APPOINTMENT:" + id + ":CREATED:EMAIL:" + sinCuenta.getId());

        assertThat(countRows("notification")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(
                "SELECT resolution FROM communication_intention WHERE appointment_id = ? "
                        + "AND channel = 'IN_APP' AND required_role = 'PATIENT'",
                String.class, id)).containsExactly("NO_CONTACT_CHANNEL");
    }

    /** §14.2/§14.6: llegada, inicio y completado son solo notificación; no abren outbox. */
    @Test
    void losEventosDeSoloNotificacionNoGeneranCommunication() throws Exception {
        Long id = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));

        mockMvc.perform(post("/api/appointments/{id}/arrive", id)
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/appointments/{id}/start", id)
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/appointments/{id}/complete", id)
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isOk());

        assertThat(countRows("communication")).isEqualTo(1);
        assertThat(countRows("communication_intention")).isEqualTo(3);
        assertThat(countRows("notification")).isEqualTo(5);
        assertThat(jdbcTemplate.queryForList(
                "SELECT appointment_id FROM notification WHERE appointment_id = ?", Long.class, id))
                .hasSize(5);
    }

    @Test
    void laCancelacionComunicaAlPacienteAlDentistaYAlCreador() throws Exception {
        Long id = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));
        mockMvc.perform(post("/api/appointments/{id}/cancel", id)
                        .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk());

        assertThat(countRows("communication")).isEqualTo(4);
        assertThat(countRows("notification")).isEqualTo(5);
        assertThat(countRows("communication_intention")).isEqualTo(9);
        assertThat(jdbcTemplate.queryForList(
                "SELECT required_role FROM communication_intention WHERE appointment_id = ? "
                        + "AND event_type = 'CANCELLED' ORDER BY id",
                String.class, id)).containsExactly("PATIENT", "PATIENT", "DENTIST", "DENTIST",
                "CREATOR", "CREATOR");
        assertThat(jdbcTemplate.queryForList(
                "SELECT address FROM communication WHERE appointment_id = ? AND type = 'CANCELLED' "
                        + "ORDER BY id", String.class, id))
                .containsOnly(patientUser.getEmail(), dentistUser.getEmail(), owner.getEmail());
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

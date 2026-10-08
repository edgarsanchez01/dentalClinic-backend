package com.dentalclinic.appointment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.patient.domain.Patient;
import com.dentalclinic.scheduling.domain.ClinicSchedule;
import com.dentalclinic.scheduling.domain.DentistSchedule;

/**
 * F6.D · Creación de citas sobre el DDL real V006 (A-01..A-08, A-14..A-16, D-F6-10, §17.5).
 */
class AppointmentCreateApiTest extends AppointmentIntegrationTestSupport {

    private static final AtomicInteger KEYS = new AtomicInteger();

    @Test
    void ownerCreaCitaYDejaAuditoriaIdempotenciaYComunicacion() throws Exception {
        String key = nextKey();
        MvcResult result = mockMvc.perform(post("/api/appointments")
                        .header("Authorization", bearer(loginToken(owner)))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(patient.getId(), dentist.getId(),
                                at(MARTES, 9, 0), at(MARTES, 10, 0))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.patientId").value(patient.getId()))
                .andExpect(jsonPath("$.dentistId").value(dentist.getId()))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.occurrenceVersion").value(1))
                .andExpect(jsonPath("$.durationMinutes").value(60))
                .andExpect(jsonPath("$.createdByUserId").value(owner.getId()))
                .andReturn();
        Long appointmentId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asLong();

        mockMvc.perform(get("/api/appointments/{id}", appointmentId)
                        .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(appointmentId));

        assertThat(appointmentEventTypes(appointmentId)).containsExactly("APPOINTMENT_CREATED");
        assertThat(countRows("appointment")).isEqualTo(1);

        var idem = jdbcTemplate.queryForMap(
                "SELECT response_status, operation, request_hash FROM http_idempotency WHERE idempotency_key = ?",
                key);
        assertThat(idem.get("operation")).isEqualTo("CREATE_APPOINTMENT");
        assertThat(((Number) idem.get("response_status")).intValue()).isEqualTo(201);
        assertThat(idem.get("request_hash")).asString().hasSize(64);

        // §14.6: al crearse, aviso al paciente (EMAIL + IN_APP) y al dentista (IN_APP).
        assertThat(countRows("communication")).isEqualTo(1);
        assertThat(countRows("notification")).isEqualTo(2);
        assertThat(countRows("communication_intention")).isEqualTo(3);
    }

    @Test
    void receptionistTambienPuedeCrearCitas() throws Exception {
        createOk(receptionist, patient.getId(), dentist.getId(), at(MARTES, 9, 0), at(MARTES, 10, 0))
                .andExpect(status().isCreated());
        assertThat(countRows("appointment")).isEqualTo(1);
    }

    @Test
    void patientSoloCreaCitasDelPerfilPropio() throws Exception {
        createOk(patientUser, patient.getId(), dentist.getId(), at(MARTES, 9, 0), at(MARTES, 10, 0))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdByUserId").value(patientUser.getId()));

        User otro = newUser(Role.PATIENT);
        Patient ajeno = patientRepository.saveAndFlush(
                Patient.createForUser(otro.getId(), "Otro", "Paciente", otro.getEmail(), null, null, null));
        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", bearer(loginToken(patientUser)))
                        .header("Idempotency-Key", nextKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(ajeno.getId(), dentist.getId(),
                                at(MARTES, 11, 0), at(MARTES, 12, 0))))
                .andExpect(status().isNotFound());
    }

    @Test
    void dentistSoloCreaCitasContigoMismoComoProfesional() throws Exception {
        User otroProfesional = newUser(Role.DENTIST);
        var otroDentist = dentistRepository.saveAndFlush(com.dentalclinic.clinic.domain.Dentist
                .create(otroProfesional.getId(), "Dentia", "Dos", "+34600000003", null, null, null, null));
        for (int day = 1; day <= 5; day++) {
            dentistScheduleRepository.saveAndFlush(DentistSchedule.create(otroDentist.getId(), day,
                    LocalTime.of(9, 0), LocalTime.of(18, 0)));
        }

        createOk(dentistUser, patient.getId(), dentist.getId(), at(MARTES, 9, 0), at(MARTES, 10, 0))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", bearer(loginToken(dentistUser)))
                        .header("Idempotency-Key", nextKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(patient.getId(), otroDentist.getId(),
                                at(MARTES, 11, 0), at(MARTES, 12, 0))))
                .andExpect(status().isForbidden());
    }

    /** D-F6-10: PATIENT sin fila {@code patient} no crea la cita y no inventamos un perfil. */
    @Test
    void patientSinPerfilDePacienteResponde409SinCrearCita() throws Exception {
        User sinPerfil = newUser(Role.PATIENT);
        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", bearer(loginToken(sinPerfil)))
                        .header("Idempotency-Key", nextKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(999L, dentist.getId(),
                                at(MARTES, 9, 0), at(MARTES, 10, 0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Completa tu perfil de paciente antes de reservar una cita."));

        assertThat(countRows("appointment")).isZero();
        assertThat(countRows("patient")).isEqualTo(1);
    }

    @Test
    void crearSinIdempotencyKeyResponde400() throws Exception {
        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", bearer(loginToken(owner)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(patient.getId(), dentist.getId(),
                                at(MARTES, 9, 0), at(MARTES, 10, 0))))
                .andExpect(status().isBadRequest());

        assertThat(countRows("appointment")).isZero();
        assertThat(countRows("http_idempotency")).isZero();
    }

    @Test
    void rangoInvalidoResponde400() throws Exception {
        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", bearer(loginToken(owner)))
                        .header("Idempotency-Key", nextKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(patient.getId(), dentist.getId(),
                                at(MARTES, 10, 0), at(MARTES, 9, 0))))
                .andExpect(status().isBadRequest());

        assertThat(countRows("appointment")).isZero();
        assertThat(countRows("http_idempotency")).isZero();
    }

    @Test
    void fueraDelHorarioDeLaClinicaResponde409() throws Exception {
        // 19:00Z está fuera de la ventana 09:00–18:00 de la clínica.
        createOwner(at(MARTES, 19, 0), at(MARTES, 20, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "El horario elegido cae fuera del horario de atención o en un descanso; selecciona otro."));

        assertThat(countRows("appointment")).isZero();
        assertThat(countRows("communication")).isZero();
    }

    @Test
    void fueraDelHorarioDelProfesionalResponde409() throws Exception {
        dentistScheduleRepository.deleteAll(dentistScheduleRepository
                .findAll().stream().filter(s -> s.getDentistId().equals(dentist.getId())).toList());
        dentistScheduleRepository.saveAndFlush(DentistSchedule.create(dentist.getId(), 2,
                LocalTime.of(13, 0), LocalTime.of(14, 0)));

        createOwner(at(MARTES, 9, 0), at(MARTES, 10, 0))
                .andExpect(status().isConflict());

        createOwner(at(MARTES, 13, 0), at(MARTES, 14, 0))
                .andExpect(status().isCreated());
    }

    @Test
    void solapamientoResponde409PeroLaAdyacenciaEsValida() throws Exception {
        createOwner(at(MARTES, 9, 0), at(MARTES, 10, 0)).andExpect(status().isCreated());

        createOwner(at(MARTES, 9, 30), at(MARTES, 10, 30))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Ese horario ya fue tomado. Selecciona otro horario."));

        createOwner(at(MARTES, 10, 0), at(MARTES, 11, 0))
                .andExpect(status().isCreated());

        assertThat(countRows("appointment")).isEqualTo(2);
    }

    @Test
    void horizonteMaximoDeReservaResponde409() throws Exception {
        LocalDate lejos = MARTES.plusDays(120);
        createOwner(at(lejos, 9, 0), at(lejos, 10, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Solo se pueden reservar citas hasta 90 días antes."));
        assertThat(countRows("appointment")).isZero();
    }

    @Test
    void citaEnElPasadoResponde400() throws Exception {
        createOwner(at(MARTES.minusDays(8), 9, 0), at(MARTES.minusDays(8), 10, 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "El horario de la cita debe estar en el futuro."));
    }

    /** A-16: la cita, su evento, sus comunicaciones, sus avisos y la clave se crean o no se crean. */
    @Test
    void unaCreacionFallidaNoDejaNingunaFilahuerfana() throws Exception {
        createOwner(at(MARTES, 20, 0), at(MARTES, 21, 0)).andExpect(status().isConflict());

        assertThat(countRows("appointment")).isZero();
        assertThat(countRows("appointment_event")).isZero();
        assertThat(countRows("communication")).isZero();
        assertThat(countRows("notification")).isZero();
        assertThat(countRows("communication_intention")).isZero();
        assertThat(countRows("http_idempotency")).isZero();
    }

    /** RF-AV-07: la antelación mínima solo la padece el paciente (A-06). */
    @Test
    void antelacionMinimaSoloSeExigeAlPaciente() throws Exception {
        setBookingPolicy(90, 48, 12);
        Instant pronto = at(MARTES.minusDays(6), 10, 0);

        mockMvc.perform(post("/api/appointments")
                        .header("Authorization", bearer(loginToken(patientUser)))
                        .header("Idempotency-Key", nextKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(patient.getId(), dentist.getId(), pronto,
                                pronto.plus(java.time.Duration.ofHours(1)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Debes reservar con al menos 48 horas de anticipación."));

        createOwner(pronto, pronto.plus(java.time.Duration.ofHours(1)))
                .andExpect(status().isCreated());
        assertThat(countRows("appointment")).isEqualTo(1);
    }

    @Test
    void pacienteDadoDeBajaResponde409() throws Exception {
        patient.update(patient.getFirstName(), patient.getLastName(), patient.getEmail(),
                patient.getPhone(), patient.getBirthDate(), patient.getAdministrativeNotes(),
                false, TestTime.now());
        patientRepository.saveAndFlush(patient);

        createOwner(at(MARTES, 9, 0), at(MARTES, 10, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "El paciente está dado de baja; no se pueden agendar citas."));
        assertThat(countRows("appointment")).isZero();
    }

    /** A-17/§17: la ventana horaria se evalúa en la zona horaria de la clínica, no en UTC. */
    @Test
    void laVentanaHorariaSeEvaluaEnLaZonaDeLaClinica() throws Exception {
        setTimezone("Europe/Madrid");

        // 06:00Z = 08:00 en Madrid: antes de abrir.
        createOwner(at(MARTES, 6, 0), at(MARTES, 7, 0)).andExpect(status().isConflict());
        // 07:00Z = 09:00 en Madrid: abre la clínica.
        createOwner(at(MARTES, 7, 0), at(MARTES, 8, 0)).andExpect(status().isCreated());
    }

    /**
     * DST (cambio de hora de otoño, 2026-10-25): las dos 02:30 locales existen una sola vez en la
     * agenda; la segunda no debe chocar con la primera ni colapsarse con ella (A-17).
     */
    @Test
    void lasDosHorasRepetidasDelCambioDeHoraNoSeSolapanNiSeColapsan() throws Exception {
        setTimezone("Europe/Madrid");
        clinicScheduleRepository.saveAndFlush(ClinicSchedule.create(clinic.getId(), 7,
                LocalTime.of(1, 0), LocalTime.of(5, 0)));
        dentistScheduleRepository.saveAndFlush(DentistSchedule.create(dentist.getId(), 7,
                LocalTime.of(1, 0), LocalTime.of(5, 0)));

        Instant antes = Instant.parse("2026-10-25T00:30:00Z");   // 02:30 CEST
        Instant despues = Instant.parse("2026-10-25T01:30:00Z"); // 02:30 CET (hora repetida)

        createOwner(antes, antes.plus(java.time.Duration.ofHours(1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.startAt").value(antes.toString()));
        createOwner(despues, despues.plus(java.time.Duration.ofHours(1)))
                .andExpect(status().isCreated());

        // Un intervalo que sí se solapa con los dos sigue rechazándose.
        createOwner(Instant.parse("2026-10-25T01:00:00Z"),
                        Instant.parse("2026-10-25T02:00:00Z"))
                .andExpect(status().isConflict());

        assertThat(countRows("appointment")).isEqualTo(2);
    }

    // ------------------------------------------------------------- helpers

    private ResultActions createOwner(Instant start, Instant end) throws Exception {
        return create(owner, patient.getId(), dentist.getId(), start, end, nextKey());
    }

    private org.springframework.test.web.servlet.ResultActions createOk(User actor, Long patientId,
            Long dentistId, Instant start, Instant end) throws Exception {
        return create(actor, patientId, dentistId, start, end, nextKey());
    }

    private org.springframework.test.web.servlet.ResultActions create(User actor, Long patientId,
            Long dentistId, Instant start, Instant end, String key) throws Exception {
        return mockMvc.perform(post("/api/appointments")
                .header("Authorization", bearer(loginToken(actor)))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(patientId, dentistId, start, end)));
    }

    private static String nextKey() {
        return "k-" + KEYS.incrementAndGet() + "-" + UUID.randomUUID();
    }
}

package com.dentalclinic.appointment.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.dentalclinic.appointment.application.AppointmentService;
import com.dentalclinic.appointment.application.ClaimedEmail;
import com.dentalclinic.appointment.application.CommunicationClaimService;
import com.dentalclinic.appointment.application.CommunicationDispatcher;
import com.dentalclinic.appointment.application.CommunicationEmailComposer;
import com.dentalclinic.appointment.application.EmailSendException;
import com.dentalclinic.appointment.application.EmailSender;
import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.appointment.domain.AppointmentEvent;
import com.dentalclinic.appointment.domain.AppointmentEventType;
import com.dentalclinic.appointment.domain.AppointmentOccurrenceSnapshot;
import com.dentalclinic.appointment.domain.Communication;
import com.dentalclinic.appointment.domain.CommunicationType;
import com.dentalclinic.appointment.domain.RecipientKind;
import com.dentalclinic.appointment.infrastructure.config.CommunicationProperties;
import com.dentalclinic.appointment.infrastructure.repository.AppointmentOccurrenceSnapshotRepository;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.patient.domain.Patient;

/** 8C: datos/DDL reales en PG16 efímero, red sustituida por EmailSender falso. */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:16:///dentalclinic_appointment_email_8c",
        "logging.level.com.dentalclinic.appointment.application.CommunicationDispatcher=WARN"
})
class AppointmentEmailNotificationIntegrationTest extends AppointmentIntegrationTestSupport {
    @Autowired private CommunicationDispatcher dispatcher;
    @Autowired private CommunicationClaimService claims;
    @Autowired private CommunicationEmailComposer composer;
    @Autowired private CommunicationProperties properties;
    @Autowired private AppointmentService appointments;
    @Autowired private RecordingEmailSender sender;
    @Autowired private Clock clock;
    @Autowired private AppointmentOccurrenceSnapshotRepository snapshots;

    @BeforeEach
    void resetSender() {
        sender.reset();
    }

    @Test
    void creadaIncluyeDatosRealesPeroNingunContenidoClinicoFinanciero() throws Exception {
        clinic.update("Consulta de prueba", "Europe/Madrid", "DESCRIPCION_PRIVADA", null,
                "Calle de prueba 12", "+34900000000", "contacto@dc.test", "POLITICA_NO_PUBLICAR",
                null, null, TestTime.now());
        clinicRepository.saveAndFlush(clinic);
        Long id = create(owner, patient, at(MARTES, 9, 0));
        jdbcTemplate.update("UPDATE appointment SET reason='DIAGNOSTICO_PRIVADO', notes='NOTA_PRIVADA', "
                + "service_name='TRATAMIENTO_PRIVADO', service_description='ODONTOGRAMA_PRIVADO', "
                + "service_reference_price=98765.4321 WHERE id=?", id);

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(sender.messages()).singleElement().satisfies(email -> {
            assertThat(email.to()).isEqualTo(patientUser.getEmail());
            assertThat(email.subject()).isEqualTo("Cita registrada — Consulta de prueba");
            assertThat(email.body()).contains("Se ha registrado una cita.", "Paciente: Paciente Uno",
                    "martes 13 de octubre de 2026 a las 11:00", "Europe/Madrid, UTC+02:00",
                    "Profesional: Dentia Perfil", "Duración prevista: 60 minutos", "Clínica: Consulta de prueba",
                    "Dirección: Calle de prueba 12", "Teléfono: +34900000000", "Correo de contacto: contacto@dc.test")
                    .doesNotContain("DIAGNOSTICO_PRIVADO", "NOTA_PRIVADA", "TRATAMIENTO_PRIVADO", "ODONTOGRAMA_PRIVADO",
                            "98765", "DESCRIPCION_PRIVADA", "POLITICA_NO_PUBLICAR", "token=", "CONFIRMED");
        });
    }

    @Test
    void reprogramadaMuestraHorarioAnteriorYNuevoYNotificaAlCreadorOriginal() throws Exception {
        Long id = create(owner, patient, at(MARTES, 9, 0));
        reschedule(id, receptionist, at(MARTES, 11, 0), 1);
        assertThat(dispatcher.dispatchOnce()).isEqualTo(3); // No entrega CREATED V1 obsoleta.
        assertThat(sender.messages()).extracting(email -> email.to())
                .containsExactlyInAnyOrder(patientUser.getEmail(), dentistUser.getEmail(), owner.getEmail());
        assertThat(sender.messages()).allSatisfy(email -> {
            assertThat(email.subject()).isEqualTo("Cita reprogramada — Clínica Dental");
            assertThat(email.body()).contains("Horario anterior: martes 13 de octubre de 2026 a las 09:00",
                    "Horario nuevo: martes 13 de octubre de 2026 a las 11:00", "UTC+00:00", "Paciente: Paciente Uno")
                    .doesNotContain("por ti", "Servicio de prueba");
        });
        assertThat(jdbcTemplate.queryForList("SELECT user_id FROM notification WHERE appointment_id=? "
                + "AND title='Cita reprogramada'", Long.class, id))
                .containsExactlyInAnyOrder(patientUser.getId(), dentistUser.getId(), owner.getId());
    }

    @Test
    void canceladaNoSePresentaComoVigenteNiSeEntregaSuCreacionPendiente() throws Exception {
        Long id = create(owner, patient, at(MARTES, 9, 0));
        appointments.cancel(id, actor(receptionist));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(3);
        assertThat(sender.messages()).extracting(email -> email.to())
                .containsExactlyInAnyOrder(patientUser.getEmail(), dentistUser.getEmail(), owner.getEmail());
        assertThat(sender.messages()).allSatisfy(email ->
                assertThat(email.body()).contains("fue cancelada y ya no está vigente", "Horario de la cita cancelada:",
                        "09:00", "UTC+00:00").doesNotContain("Te esperamos", "Cancelaste", "registrado una cita"));
        assertThat(communicationRepository.findAll().stream().filter(row -> row.getType() == CommunicationType.CREATED))
                .allSatisfy(row -> assertThat(row.getSupersededAt()).isNotNull());
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void matrizDeduplicaPorIdentidadDelCreadorAunqueOtroActorCancele(Role creatorRole) throws Exception {
        User creator = switch (creatorRole) {
            case OWNER -> owner;
            case DENTIST -> dentistUser;
            case RECEPTIONIST -> receptionist;
            case PATIENT -> patientUser;
        };
        Long id = create(creator, patient, at(MARTES, 9, 0));
        appointments.cancel(id, actor(creatorRole == Role.OWNER ? receptionist : owner));
        Set<String> expected = Stream.of(patientUser.getEmail(), dentistUser.getEmail(), creator.getEmail())
                .collect(java.util.stream.Collectors.toSet());
        assertThat(dispatcher.dispatchOnce()).isEqualTo(expected.size());
        assertThat(sender.messages()).extracting(email -> email.to()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(dispatcher.dispatchOnce()).isZero();
    }

    @Test
    void pacienteSinCuentaRecibeEnSuContactoSinInferirUnaCuenta() throws Exception {
        Patient administrative = patientRepository.saveAndFlush(Patient.create("Solo", "Contacto",
                "contacto-paciente@dc.test", null, null, null));
        create(owner, administrative, at(MARTES, 9, 0));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(sender.messages().getFirst().to()).isEqualTo("contacto-paciente@dc.test");
        assertThat(sender.messages().getFirst().body()).contains("Paciente: Solo Contacto");
        assertThat(communicationRepository.findAll().getFirst().getRecipientKind()).isEqualTo(RecipientKind.PATIENT);
    }

    @Test
    void correoDeCuentaInvalidoUsaContactoAdministrativoValido() throws Exception {
        jdbcTemplate.update("UPDATE users SET email='invalid-address' WHERE id=?", patientUser.getId());
        create(owner, patient, at(MARTES, 9, 0));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(sender.messages().getFirst().to()).isEqualTo(patient.getEmail());
        assertThat(communicationRepository.findAll().getFirst().getRecipientKind()).isEqualTo(RecipientKind.PATIENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "invalid-address"})
    void sinCorreoValidoNoSeInventaCanalNiSeBloqueaLaCita(String email) throws Exception {
        Patient administrative = patientRepository.saveAndFlush(Patient.create("Sin", "Correo", email, null, null, null));
        Long id = create(owner, administrative, at(MARTES, 9, 0));
        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(countRows("appointment")).isEqualTo(1);
        assertThat(countRows("communication")).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT resolution FROM communication_intention "
                + "WHERE appointment_id=? AND channel='EMAIL'", String.class, id)).isEqualTo("NO_CONTACT_CHANNEL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "malformed", "inconsistent", "ambiguous"})
    void noInventaHorarioAnteriorSiElHistorialNoEsFiable(String history) throws Exception {
        Long id = create(owner, patient, at(MARTES, 9, 0));
        reschedule(id, owner, at(MARTES, 11, 0), 1);
        switch (history) {
            case "missing" -> jdbcTemplate.update("UPDATE appointment_event SET metadata=null WHERE appointment_id=?", id);
            case "malformed" -> jdbcTemplate.update("UPDATE appointment_event SET metadata='{}'::jsonb WHERE appointment_id=?", id);
            case "inconsistent" -> jdbcTemplate.update("UPDATE appointment_event SET metadata="
                    + "jsonb_set(metadata,'{startAt}','\"2026-10-13T12:00:00Z\"') WHERE appointment_id=? "
                    + "AND event_type='APPOINTMENT_RESCHEDULED'", id);
            case "ambiguous" -> appointmentEventRepository.saveAndFlush(AppointmentEvent.create(id,
                    AppointmentEventType.APPOINTMENT_RESCHEDULED, owner.getId(), 2, null));
            default -> throw new AssertionError(history);
        }
        assertThat(dispatcher.dispatchOnce()).isEqualTo(3);
        assertThat(sender.messages()).allSatisfy(email -> assertThat(email.body())
                .contains("Horario nuevo:", "11:00").doesNotContain("Horario anterior:", "09:00"));
    }

    @Test
    void historialAntiguoPuedeUsarSnapshotRealDeLaOcurrenciaAnterior() throws Exception {
        Long id = create(owner, patient, at(MARTES, 9, 0));
        snapshots.saveAndFlush(AppointmentOccurrenceSnapshot.capture(appointmentRepository.findById(id).orElseThrow(),
                clinic.getTimezone(), TestTime.now()));
        reschedule(id, owner, at(MARTES, 11, 0), 1);
        jdbcTemplate.update("UPDATE appointment_event SET metadata=null WHERE appointment_id=?", id);
        assertThat(dispatcher.dispatchOnce()).isEqualTo(3);
        assertThat(sender.messages()).allSatisfy(email -> assertThat(email.body())
                .contains("Horario anterior: martes 13 de octubre de 2026 a las 09:00", "Horario nuevo:", "11:00"));
    }

    @Test
    void reprogramacionesSucesivasYReplayNoDuplicanNiEntreganVersionesViejas() throws Exception {
        Long id = create(owner, patient, at(MARTES, 9, 0));
        reschedule(id, owner, at(MARTES, 11, 0), 1);
        reschedule(id, owner, at(MARTES, 13, 0), 2);
        long count = countRows("communication");
        assertThatThrownBy(() -> reschedule(id, owner, at(MARTES, 13, 0), 2))
                .isInstanceOf(com.dentalclinic.scheduling.domain.ScheduleConflictException.class);
        assertThat(countRows("communication")).isEqualTo(count);
        assertThat(dispatcher.dispatchOnce()).isEqualTo(3);
        assertThat(sender.messages()).allSatisfy(email -> assertThat(email.body())
                .contains("Horario anterior: martes 13 de octubre de 2026 a las 11:00",
                        "Horario nuevo: martes 13 de octubre de 2026 a las 13:00").doesNotContain("09:00"));
        assertThat(dispatcher.dispatchOnce()).isZero();
    }

    @Test
    void replayDeCreacionConservaUnaSolaFilaYUnSoloEnvio() throws Exception {
        String key = UUID.randomUUID().toString();
        Long id = create(owner, patient, at(MARTES, 9, 0), key);
        assertThat(create(owner, patient, at(MARTES, 9, 0), key)).isEqualTo(id);
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(create(owner, patient, at(MARTES, 9, 0), key)).isEqualTo(id);
        assertThat(countRows("communication")).isEqualTo(1);
        assertThat(dispatcher.dispatchOnce()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "NO_SHOW", "STARTED"})
    void creacionPendienteNoSeAnunciaComoVigenteTrasCierreOInicio(String change) throws Exception {
        Long id = create(owner, patient, at(MARTES, 9, 0));
        Long created = communicationRepository.findAll().getFirst().getId();
        switch (change) {
            case "COMPLETED" -> appointments.complete(id, actor(dentistUser), 1, java.util.UUID.randomUUID().toString());
            case "NO_SHOW" -> {
                TestTime.advance(Duration.between(TestTime.now(), at(MARTES, 9, 0)));
                appointments.markNoShow(id, actor(owner));
            }
            case "STARTED" -> TestTime.advance(Duration.between(TestTime.now(), at(MARTES, 9, 0)));
            default -> throw new AssertionError(change);
        }
        dispatcher.dispatchOnce();
        assertThat(sender.messages()).noneSatisfy(email -> assertThat(email.subject()).startsWith("Cita registrada"));
        assertThat(communicationRepository.findById(created).orElseThrow().getSupersededAt()).isNotNull();
    }

    @Test
    void retryDeUnaCreacionCanceladaNoEnviaUnAvisoVigente() throws Exception {
        Long id = create(owner, patient, at(MARTES, 9, 0));
        sender.alwaysFail();
        assertThat(dispatcher.dispatchOnce()).isZero();
        appointments.cancel(id, actor(owner));
        TestTime.advance(Duration.ofMinutes(2));
        sender.succeed();
        assertThat(dispatcher.dispatchOnce()).isEqualTo(3);
        assertThat(sender.messages()).allSatisfy(email -> assertThat(email.subject()).startsWith("Cita cancelada"));
    }

    @Test
    void workerAntiguoNoPreparaNiSobrescribeOtroLease() throws Exception {
        create(owner, patient, at(MARTES, 9, 0));
        ClaimedEmail old = claims.claim(TestTime.now(), 1, Duration.ofMinutes(5)).getFirst();
        TestTime.advance(Duration.ofMinutes(5));
        assertThat(claims.prepareSend(old)).isFalse();
        ClaimedEmail current = claims.claim(TestTime.now(), 1, Duration.ofMinutes(5)).getFirst();
        claims.scheduleRetry(old, "old-error", TestTime.now(), TestTime.now());
        claims.markFailed(old, "old-error", TestTime.now());
        claims.supersede(old, TestTime.now());
        assertThat(claims.markSent(old, TestTime.now())).isFalse();
        assertThat(claims.prepareSend(current)).isTrue();
        sender.send(composer.compose(current));
        assertThat(claims.markSent(current, TestTime.now())).isTrue();
        assertThat(communicationRepository.findById(old.id()).orElseThrow().getAttempts()).isEqualTo(2);
        assertThat(communicationRepository.findById(old.id()).orElseThrow().getSupersededAt()).isNull();
    }

    @Test
    void cancelacionDuranteComposicionSeDetectaAntesDeLaRed() throws Exception {
        Long id = create(owner, patient, at(MARTES, 9, 0));
        CommunicationEmailComposer changing = spy(
                org.springframework.test.util.AopTestUtils.<CommunicationEmailComposer>getUltimateTargetObject(composer));
        doAnswer(invocation -> {
            Object message = invocation.callRealMethod();
            appointments.cancel(id, actor(owner));
            return message;
        }).when(changing).compose(any());
        assertThat(new CommunicationDispatcher(claims, changing, properties, Optional.of(sender), clock, feedback)
                .dispatchOnce()).isZero();
        assertThat(sender.sentCount()).isZero();
        assertThat(dispatcher.dispatchOnce()).isEqualTo(3);
    }

    @Test
    void cancelacionDuranteEnvioNoMantieneTransaccionNiOcultaAceptacion() throws Exception {
        Long id = create(owner, patient, at(MARTES, 9, 0));
        EmailSender cancelling = email -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            appointments.cancel(id, actor(owner));
            sender.send(email);
        };
        assertThat(dispatcher(cancelling).dispatchOnce()).isEqualTo(1);
        assertThat(communicationRepository.findAll().stream().filter(row -> row.getType() == CommunicationType.CREATED))
                .allSatisfy(row -> assertThat(row.getStatus().name()).isEqualTo("SENT"));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(3);
    }

    @Test
    void reprogramacionDuranteEnvioConservaAceptacionYSupersededSinReenviarVersionVieja() throws Exception {
        Long id = create(owner, patient, at(MARTES, 9, 0));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        sender.reset();
        reschedule(id, owner, at(MARTES, 11, 0), 1);
        assertThat(dispatcher(email -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            reschedule(id, owner, at(MARTES, 13, 0), 2);
            sender.send(email);
        }).dispatchOnce()).isEqualTo(1);
        var old = communicationRepository.findAll().stream()
                .filter(row -> row.getType() == CommunicationType.RESCHEDULED && row.getOccurrenceVersion() == 2).toList();
        assertThat(old).hasSize(3).allSatisfy(row -> assertThat(row.getSupersededAt()).isNotNull());
        assertThat(old.stream().filter(row -> row.getStatus().name().equals("SENT"))).hasSize(1);
        sender.reset();
        assertThat(dispatcher.dispatchOnce()).isEqualTo(3);
        assertThat(sender.messages()).allSatisfy(email -> assertThat(email.body()).contains("Horario nuevo:", "13:00"));
        assertThat(dispatcher.dispatchOnce()).isZero();
    }

    @Test
    void falloDePersistenciaTrasAceptacionNoSeClasificaComoErrorSmtp() throws Exception {
        create(owner, patient, at(MARTES, 9, 0));
        jdbcTemplate.execute("ALTER TABLE communication ADD CONSTRAINT test_reject_sent CHECK (status <> 'SENT') NOT VALID");
        try {
            assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        } finally {
            jdbcTemplate.execute("ALTER TABLE communication DROP CONSTRAINT test_reject_sent");
        }
        Communication pending = communicationRepository.findAll().getFirst();
        assertThat(pending.getLastError()).isNull();
        assertThat(pending.getStatus().name()).isEqualTo("PENDING");
        assertThat(dispatcher.dispatchOnce()).isZero();
        TestTime.advance(properties.dispatch().lease().plusSeconds(1));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(sender.sentCount()).isEqualTo(2); // Riesgo residual documentado, no exactly-once externo.
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void errorDelProveedorNoFiltraCuerpoDestinatarioNiSecretos(CapturedOutput output) throws Exception {
        create(owner, patient, at(MARTES, 9, 0));
        assertThat(dispatcher(email -> {
            throw new EmailSendException(email.to() + " " + email.body() + " TOKEN_PRIVADO_NO_LOG");
        }).dispatchOnce()).isZero();
        assertThat(communicationRepository.findAll().getFirst().getLastError()).isEqualTo("EmailSendException");
        assertThat(output.getAll()).contains("EmailSendException")
                .doesNotContain("TOKEN_PRIVADO_NO_LOG", patientUser.getEmail(), "Paciente Uno", "Dentia Perfil");
        assertThat(sender.messages()).isEmpty();
    }

    @Test
    void sinSmtpNoReclamaNiConsumeIntentos() throws Exception {
        create(owner, patient, at(MARTES, 9, 0));
        assertThat(properties.email().enabled()).isFalse();
        assertThat(properties.dispatch().enabled()).isFalse();
        assertThat(new CommunicationDispatcher(claims, composer, properties, Optional.empty(), clock, feedback)
                .dispatchOnce()).isZero();
        assertThat(communicationRepository.findAll()).allSatisfy(row -> {
            assertThat(row.getAttempts()).isZero();
            assertThat(row.getStatus().name()).isEqualTo("PENDING");
        });
    }

    static Stream<Arguments> timezones() {
        return Stream.of(
                Arguments.of("America/Mexico_City", "2026-10-13T00:30:00Z", "lunes 12 de octubre de 2026", "18:30", "UTC-06:00"),
                Arguments.of("Europe/Madrid", "2026-10-25T00:30:00Z", "domingo 25 de octubre de 2026", "02:30", "UTC+02:00"),
                Arguments.of("Europe/Madrid", "2026-10-25T01:30:00Z", "domingo 25 de octubre de 2026", "02:30", "UTC+01:00"),
                Arguments.of("Europe/Madrid", "2026-03-29T01:30:00Z", "domingo 29 de marzo de 2026", "03:30", "UTC+02:00"));
    }

    @ParameterizedTest
    @MethodSource("timezones")
    void avisosRespetanCambioDeDiaYAmbosLadosDeDst(String zone, String start, String date, String time, String offset) {
        setTimezone(zone);
        Instant instant = Instant.parse(start);
        TestTime.advance(Duration.between(TestTime.now(), instant.minusSeconds(60)));
        Appointment appointment = appointmentRepository.saveAndFlush(Appointment.create(patient.getId(), dentist.getId(),
                owner.getId(), instant, instant.plusSeconds(3600), 60, null, null));
        communicationRepository.saveAndFlush(Communication.create(appointment.getId(), CommunicationType.CREATED,
                RecipientKind.USER, null, patientUser.getId(), patientUser.getEmail(), UUID.randomUUID().toString(), 1));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(sender.messages().getFirst().body()).contains(date, time, zone, offset)
                .doesNotContain("Dirección:", "Teléfono:", "Correo de contacto:", "null");
    }

    private CommunicationDispatcher dispatcher(EmailSender emailSender) {
        return new CommunicationDispatcher(claims, composer, properties, Optional.of(emailSender), clock, feedback);
    }

    private AuthenticatedUser actor(User user) {
        return new AuthenticatedUser(user.getId(), user.getRole());
    }

    private void reschedule(Long id, User user, Instant start, int version) {
        appointments.reschedule(id, actor(user), new AppointmentService.RescheduleData(start, start.plusSeconds(3600), version));
    }

    private Long create(User user, Patient target, Instant start) throws Exception {
        return create(user, target, start, UUID.randomUUID().toString());
    }

    private Long create(User user, Patient target, Instant start, String key) throws Exception {
        var result = mockMvc.perform(post("/api/appointments").header("Authorization", bearer(loginToken(user)))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(createBody(target.getId(), dentist.getId(), start, start.plusSeconds(3600))))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }
}

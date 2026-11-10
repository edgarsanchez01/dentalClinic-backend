package com.dentalclinic.appointment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import com.dentalclinic.appointment.application.AppointmentReminderJob;
import com.dentalclinic.appointment.application.AppointmentService;
import com.dentalclinic.appointment.application.ClaimedEmail;
import com.dentalclinic.appointment.application.CommunicationClaimService;
import com.dentalclinic.appointment.application.CommunicationDispatcher;
import com.dentalclinic.appointment.application.CommunicationEmailComposer;
import com.dentalclinic.appointment.application.EmailSendException;
import com.dentalclinic.appointment.application.OutboundEmail;
import com.dentalclinic.appointment.application.ReminderAuditRecorder;
import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.appointment.domain.AppointmentEvent;
import com.dentalclinic.appointment.domain.AppointmentEventType;
import com.dentalclinic.appointment.domain.Communication;
import com.dentalclinic.appointment.domain.CommunicationStatus;
import com.dentalclinic.appointment.domain.CommunicationType;
import com.dentalclinic.appointment.infrastructure.config.AppointmentReminderProperties;
import com.dentalclinic.appointment.infrastructure.config.CommunicationProperties;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.application.UserService;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/** T4.3: contenido real, auditoría sobre DDL existente y aceptación exclusivamente por emisor falso. */
class AppointmentReminderContentAuditIntegrationTest extends AppointmentIntegrationTestSupport {

    @Autowired private AppointmentReminderJob producer;
    @Autowired private AppointmentService appointments;
    @Autowired private CommunicationEmailComposer composer;
    @Autowired private ReminderAuditRecorder audit;
    @Autowired private UserService users;
    @Autowired private Clock clock;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RecordingEmailSender sender;
    @Autowired private CommunicationProperties properties;
    @Autowired private AppointmentReminderProperties reminderProperties;

    private CommunicationClaimService delivery;
    private CommunicationDispatcher dispatcher;

    @BeforeEach
    void prepareDelivery() {
        sender.reset();
        delivery = service(audit);
        dispatcher = dispatcher(delivery);
    }

    @Test
    void contenidoPersonalizadoYNotificacionUnicaConDatosDisponibles() {
        clinic.update("Clínica Sonrisa", "Europe/Madrid", null, null, "Calle Mayor 12", "+34900000000",
                "contacto@dc.test", null, null, null, TestTime.now());
        clinicRepository.saveAndFlush(clinic);
        Appointment appointment = generated(TestTime.now().plus(Duration.ofHours(2)));
        assertThat(producer.generateOnce()).isZero();
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(sender.messages()).singleElement().satisfies(email -> {
            assertThat(email.to()).isEqualTo(patientUser.getEmail());
            assertThat(email.subject()).isEqualTo("Recordatorio de tu cita dental — Clínica Sonrisa");
            assertThat(email.body()).contains("Hola, Paciente Uno:", "martes 6 de octubre de 2026 a las 12:00",
                    "Europe/Madrid, UTC+02:00", "Profesional: Dentia Perfil", "Clínica: Clínica Sonrisa",
                    "Dirección: Calle Mayor 12", "Teléfono: +34900000000", "Correo de contacto: contacto@dc.test",
                    "¡Te esperamos!").doesNotContain("faltan cinco horas", "null");
        });
        assertThat(notificationRepository.findAll()).singleElement().satisfies(notification -> {
            assertThat(notification.getTitle()).isEqualTo("Recordatorio de tu cita");
            assertThat(notification.getBody()).contains("12:00", "Europe/Madrid", "Clínica Sonrisa", "Dentia Perfil");
        });
        assertThat(communicationIntentionRepository.count()).isEqualTo(2);
        assertThat(events(appointment)).hasSize(1);
    }

    static Stream<Arguments> timezoneCases() {
        return Stream.of(
                Arguments.of("America/Mexico_City", "2026-10-13T00:30:00Z", "lunes 12 de octubre de 2026", "18:30", "UTC-06:00"),
                Arguments.of("Europe/Madrid", "2026-10-25T00:30:00Z", "domingo 25 de octubre de 2026", "02:30", "UTC+02:00"),
                Arguments.of("Europe/Madrid", "2026-10-25T01:30:00Z", "domingo 25 de octubre de 2026", "02:30", "UTC+01:00"),
                Arguments.of("Europe/Madrid", "2026-03-29T01:30:00Z", "domingo 29 de marzo de 2026", "03:30", "UTC+02:00"));
    }

    @ParameterizedTest
    @MethodSource("timezoneCases")
    void respetaZonaCambioDeDiaYAmbosCambiosDst(String zone, String start, String date,
            String time, String offset) {
        setTimezone(zone);
        Instant instant = Instant.parse(start);
        TestTime.advance(Duration.between(TestTime.now(), instant.minusSeconds(30 * 60)));
        generated(instant);
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(sender.messages()).singleElement().satisfies(email ->
                assertThat(email.body()).contains(date, time, zone, offset));
        assertThat(notificationRepository.findAll()).singleElement().satisfies(notification ->
                assertThat(notification.getBody()).contains(date, time, zone, offset));
    }

    @Test
    void datosOpcionalesAusentesNoSeInventan() {
        generated(TestTime.now().plusSeconds(60));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(sender.messages()).singleElement().satisfies(email ->
                assertThat(email.body()).contains("08:01", "UTC", "Paciente Uno", "Clínica Dental")
                        .doesNotContain("Dirección:", "Teléfono:", "Correo de contacto:", "null", "faltan cinco horas"));
    }

    @Test
    void encolarYSmtpDeshabilitadoNoCreanEventosTerminales() {
        Appointment appointment = generated();
        assertThat(events(appointment)).isEmpty();
        assertThat(properties.email().enabled()).isFalse();
        assertThat(reminderProperties.emailDeliveryEnabled()).isFalse();
        CommunicationDispatcher noSmtp = new CommunicationDispatcher(delivery, composer, properties, Optional.empty(), clock, feedback);
        assertThat(noSmtp.dispatchOnce()).isZero();
        assertThat(events(appointment)).isEmpty();
        assertThat(reminder(appointment).getStatus()).isEqualTo(CommunicationStatus.PENDING);
    }

    @Test
    void sentSoloTrasAceptacionConActorTecnicoYVersionCorrecta() {
        Appointment appointment = generated();
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(events(appointment)).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo(AppointmentEventType.REMINDER_SENT);
            assertThat(event.getOccurrenceVersion()).isEqualTo(1);
            assertThat(event.getActorUserId()).isEqualTo(owner.getId());
            assertThat(users.findById(event.getActorUserId())).isPresent();
            assertThat(event.getMetadata()).containsEntry("source", "SCHEDULER")
                    .containsEntry("automatic", true).containsEntry("humanAction", false)
                    .containsEntry("actorReference", "APPOINTMENT_CREATOR")
                    .containsEntry("result", "SMTP_ACCEPTED").containsEntry("previousStatus", "PENDING")
                    .containsEntry("status", "SENT").containsEntry("attempt", 1)
                    .containsEntry("occurrenceVersion", 1).containsEntry("lateResult", false)
                    .containsEntry("templateVersion", "appointment-reminder-text-v1");
            assertThat(((Number) event.getMetadata().get("communicationId")).longValue())
                    .isEqualTo(reminder(appointment).getId());
            assertThat(event.getMetadata()).doesNotContainKeys("address", "patientEmail", "password");
        });
    }

    @Test
    void referenciaTecnicaNoExigeCuentaActivaNiCambiaPermisos() {
        Appointment appointment = generated();
        owner.deactivate();
        userRepository.saveAndFlush(owner);
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(events(appointment)).singleElement().satisfies(event ->
                assertThat(event.getActorUserId()).isEqualTo(owner.getId()));
        assertThat(users.getById(owner.getId()).isActive()).isFalse();
        assertThat(users.getById(owner.getId()).getRole()).isEqualTo(Role.OWNER);
    }

    @Test
    void fallosIntermediosNoCreanFailedYAgotamientoLoCreaUnaVez() {
        Appointment appointment = generated();
        sender.alwaysFail();
        for (int attempt = 1; attempt <= properties.dispatch().maxAttempts(); attempt++) {
            assertThat(dispatcher.dispatchOnce()).isZero();
            if (attempt < properties.dispatch().maxAttempts()) {
                assertThat(events(appointment)).isEmpty();
                TestTime.advance(Duration.ofMinutes(30));
            }
        }
        Communication communication = reminder(appointment);
        assertThat(communication.getStatus()).isEqualTo(CommunicationStatus.FAILED);
        assertThat(communication.getAttempts()).isEqualTo(4);
        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(events(appointment)).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo(AppointmentEventType.REMINDER_FAILED);
            assertThat(event.getMetadata()).containsEntry("result", "ATTEMPTS_EXHAUSTED")
                    .containsEntry("attempt", 4).containsEntry("status", "FAILED")
                    .containsEntry("automatic", true).containsEntry("superseded", false);
            assertThat(event.getMetadata().get("error")).isEqualTo("EmailSendException");
        });
    }

    @Test
    void retryIntermedioLuegoExitoSoloCreaSent() {
        Appointment appointment = generated();
        sender.alwaysFail();
        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(events(appointment)).isEmpty();
        TestTime.advance(Duration.ofMinutes(2));
        sender.succeed();
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(events(appointment)).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo(AppointmentEventType.REMINDER_SENT);
            assertThat(event.getMetadata()).containsEntry("attempt", 2);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "EXPIRED", "OBSOLETE"})
    void invalidacionNoEsFalloSmtpNiGeneraFailed(String reason) {
        Appointment appointment = generated();
        if (reason.equals("CANCELLED")) {
            appointments.cancel(appointment.getId(), new AuthenticatedUser(owner.getId(), Role.OWNER));
        } else if (reason.equals("EXPIRED")) {
            TestTime.advance(Duration.ofHours(2));
        } else {
            jdbcTemplate.update("UPDATE communication SET superseded_at = now() WHERE id = ?", reminder(appointment).getId());
        }
        dispatcher.dispatchOnce(); // Los avisos normales de cancelación pueden enviarse al falso.
        assertThat(events(appointment)).isEmpty();
    }

    @Test
    void resultadoDeVersionViejaAceptadoTrasReprogramarMantieneVersionYObsolescencia() {
        Appointment appointment = generated();
        ClaimedEmail sending = started();
        OutboundEmail prepared = composer.compose(sending);
        appointments.reschedule(appointment.getId(), new AuthenticatedUser(receptionist.getId(), Role.RECEPTIONIST),
                new AppointmentService.RescheduleData(TestTime.now().plus(Duration.ofHours(3)),
                        TestTime.now().plus(Duration.ofHours(3)).plusSeconds(15 * 60), 1));
        sender.send(prepared); // Aceptación de la copia preparada antes del cambio.
        delivery.recordReminderSent(sending, TestTime.now());
        assertThat(events(appointment)).singleElement().satisfies(event -> {
            assertThat(event.getOccurrenceVersion()).isEqualTo(1);
            assertThat(event.getActorUserId()).isEqualTo(owner.getId());
            assertThat(event.getMetadata()).containsEntry("occurrenceVersion", 1).containsEntry("superseded", true);
        });
        assertThat(reminder(appointment).getSupersededAt()).isNotNull();
        assertThat(reminder(appointment).getStatus()).isEqualTo(CommunicationStatus.SENT);
        assertThat(producer.generateOnce()).isEqualTo(1);
    }

    @Test
    void dosCierresConcurrentesDeAceptacionSoloCreanUnEvento() throws Exception {
        Appointment appointment = generated();
        ClaimedEmail sending = started();
        sender.send(composer.compose(sending));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> { await(start); delivery.recordReminderSent(sending, TestTime.now()); });
            Future<?> second = pool.submit(() -> { await(start); delivery.recordReminderSent(sending, TestTime.now()); });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(events(appointment)).hasSize(1);
        assertThat(sender.sentCount()).isEqualTo(1);
        Instant sentAt = reminder(appointment).getSentAt();
        delivery.recordReminderSent(sending, TestTime.now().plusSeconds(60));
        assertThat(events(appointment)).hasSize(1);
        assertThat(reminder(appointment).getSentAt()).isEqualTo(sentAt);
    }

    @Test
    void dosCierresConcurrentesDeFalloTerminalSoloCreanUnEvento() throws Exception {
        Appointment appointment = generated();
        jdbcTemplate.update("UPDATE communication SET attempts = 3 WHERE id = ?", reminder(appointment).getId());
        ClaimedEmail sending = started();
        sender.alwaysFail();
        assertThatThrownBy(() -> sender.send(composer.compose(sending))).isInstanceOf(EmailSendException.class);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = pool.submit(() -> { await(start); return failed(sending); });
            Future<Boolean> second = pool.submit(() -> { await(start); return failed(sending); });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
        assertThat(events(appointment)).singleElement().satisfies(event ->
                assertThat(event.getEventType()).isEqualTo(AppointmentEventType.REMINDER_FAILED));
    }

    @Test
    void falloViejoNoSobrescribeSentNiCreaOtroEvento() {
        Appointment appointment = generated();
        ClaimedEmail old = started();
        TestTime.advance(Duration.ofMinutes(6));
        ClaimedEmail newer = started();
        sender.send(composer.compose(newer));
        delivery.recordReminderSent(newer, TestTime.now());
        Long eventId = events(appointment).getFirst().getId();
        assertThat(failed(old)).isFalse();
        delivery.recordReminderSent(old, TestTime.now().plusSeconds(60));
        assertThat(events(appointment)).singleElement().satisfies(event ->
                assertThat(event.getId()).isEqualTo(eventId));
        assertThat(reminder(appointment).getLastError()).isNull();
    }

    @Test
    void aceptacionTardiaTrasFailedAgregaEvidenciaSinBorrarAuditoriaAnterior() {
        Appointment appointment = generated();
        jdbcTemplate.update("UPDATE communication SET attempts = 2 WHERE id = ?", reminder(appointment).getId());
        ClaimedEmail old = started();
        OutboundEmail prepared = composer.compose(old);
        TestTime.advance(Duration.ofMinutes(6));
        ClaimedEmail newer = started();
        sender.alwaysFail();
        assertThatThrownBy(() -> sender.send(composer.compose(newer))).isInstanceOf(EmailSendException.class);
        assertThat(failed(newer)).isTrue();
        Long failedEventId = events(appointment).getFirst().getId();
        sender.succeed();
        sender.send(prepared);
        delivery.recordReminderSent(old, TestTime.now());
        assertThat(events(appointment)).extracting(AppointmentEvent::getEventType)
                .containsExactly(AppointmentEventType.REMINDER_FAILED, AppointmentEventType.REMINDER_SENT);
        assertThat(events(appointment).getFirst().getId()).isEqualTo(failedEventId);
        assertThat(events(appointment).getLast().getMetadata()).containsEntry("lateResult", true)
                .containsEntry("previousStatus", "FAILED").containsEntry("attempt", 3).containsEntry("attempts", 4);
        assertThat(failed(newer)).isFalse();
        assertThat(events(appointment)).hasSize(2);
    }

    @Test
    void errorDeAuditoriaTrasAceptacionRevierteEstadoYEventoSinInventarFalloSmtp() {
        Appointment appointment = generated();
        ReminderAuditRecorder failingAudit = failingAudit();
        assertThat(dispatcher(service(failingAudit)).dispatchOnce()).isEqualTo(1); // Aceptación real del falso.
        assertThat(sender.sentCount()).isEqualTo(1);
        assertThat(reminder(appointment).getStatus()).isEqualTo(CommunicationStatus.PENDING);
        assertThat(reminder(appointment).getLastError()).isNull();
        assertThat(events(appointment)).isEmpty();
        TestTime.advance(Duration.ofMinutes(6));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(events(appointment)).singleElement().satisfies(event ->
                assertThat(event.getEventType()).isEqualTo(AppointmentEventType.REMINDER_SENT));
    }

    @Test
    void errorDeAuditoriaDeFailedTambienRevierteLaTransicion() {
        Appointment appointment = generated();
        jdbcTemplate.update("UPDATE communication SET attempts = 3 WHERE id = ?", reminder(appointment).getId());
        ClaimedEmail sending = started();
        assertThatThrownBy(() -> service(failingAudit()).recordReminderFailure(sending, "fallo falso",
                TestTime.now().plusSeconds(60), TestTime.now())).isInstanceOf(IllegalStateException.class);
        assertThat(reminder(appointment).getStatus()).isEqualTo(CommunicationStatus.PENDING);
        assertThat(events(appointment)).isEmpty();
    }

    @Test
    void reservaSinIntentoNoPuedeFabricarEventoSent() {
        Appointment appointment = generated();
        ClaimedEmail reserved = delivery.claim(TestTime.now(), 10, Duration.ofMinutes(5)).getFirst();
        assertThatThrownBy(() -> delivery.recordReminderSent(reserved, TestTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(events(appointment)).isEmpty();
        assertThat(reminder(appointment).getStatus()).isEqualTo(CommunicationStatus.PENDING);
    }

    @ParameterizedTest
    @EnumSource(value = CommunicationType.class, names = {"CREATED", "RESCHEDULED", "CANCELLED"})
    void plantillasNormales8CUsanContextoReal(CommunicationType type) {
        Appointment appointment = appointmentRepository.saveAndFlush(Appointment.create(patient.getId(), dentist.getId(),
                owner.getId(), TestTime.now().plusSeconds(3600), TestTime.now().plusSeconds(4500), 15, null, null));
        if (type == CommunicationType.CANCELLED) {
            appointment.cancel(TestTime.now());
            appointmentRepository.saveAndFlush(appointment);
        }
        OutboundEmail email = composer.compose(new ClaimedEmail(1L, patientUser.getEmail(), type, 1, 1,
                appointment.getId(), TestTime.now().plusSeconds(60)));
        String expectedSubject = switch (type) {
            case CREATED -> "Cita registrada";
            case RESCHEDULED -> "Cita reprogramada";
            case CANCELLED -> "Cita cancelada";
            default -> throw new IllegalArgumentException();
        };
        assertThat(email.subject()).isEqualTo(expectedSubject + " — Clínica Dental");
        assertThat(email.body()).contains("Paciente: Paciente Uno", "Profesional: Dentia Perfil", "UTC+00:00")
                .doesNotContain("Este es un aviso de servicio sobre tu cita.");
    }

    private Appointment generated() {
        return generated(TestTime.now().plus(Duration.ofHours(2)));
    }

    private Appointment generated(Instant start) {
        Appointment appointment = appointmentRepository.saveAndFlush(Appointment.create(patient.getId(),
                dentist.getId(), owner.getId(), start, start.plusSeconds(15 * 60), 15, null, null));
        assertThat(producer.generateOnce()).isEqualTo(1);
        return appointment;
    }

    private Communication reminder(Appointment appointment) {
        return communicationRepository.findAll().stream().filter(email -> email.getAppointmentId().equals(appointment.getId())
                && email.getType() == CommunicationType.REMINDER).findFirst().orElseThrow();
    }

    private List<AppointmentEvent> events(Appointment appointment) {
        return appointmentEventRepository.findByAppointmentIdOrderByCreatedAtAscIdAsc(appointment.getId()).stream()
                .filter(event -> event.getEventType() == AppointmentEventType.REMINDER_SENT
                        || event.getEventType() == AppointmentEventType.REMINDER_FAILED).toList();
    }

    private ClaimedEmail started() {
        ClaimedEmail reservation = delivery.claim(TestTime.now(), 10, Duration.ofMinutes(5)).getFirst();
        return delivery.prepareReminderSend(reservation).orElseThrow();
    }

    private boolean failed(ClaimedEmail email) {
        return delivery.recordReminderFailure(email, "fallo SMTP falso", TestTime.now().plusSeconds(60), TestTime.now());
    }

    private ReminderAuditRecorder failingAudit() {
        ReminderAuditRecorder recorder = spy(new ReminderAuditRecorder(appointmentEventRepository, users));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("fallo de persistencia de auditoría simulado");
        }).when(recorder).record(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
        return recorder;
    }

    private CommunicationClaimService service(ReminderAuditRecorder recorder) {
        CommunicationClaimService target = new CommunicationClaimService(communicationRepository,
                new AppointmentReminderProperties(false, 50, 10, null, null, true), appointmentRepository, clock,
                recorder, properties);
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (CommunicationClaimService) factory.getProxy();
    }

    private CommunicationDispatcher dispatcher(CommunicationClaimService claims) {
        return new CommunicationDispatcher(claims, composer, properties, Optional.of(sender), clock, feedback);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout de coordinación");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}

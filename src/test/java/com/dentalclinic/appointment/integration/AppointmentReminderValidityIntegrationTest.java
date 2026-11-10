package com.dentalclinic.appointment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.dentalclinic.appointment.application.AppointmentReminderJob;
import com.dentalclinic.appointment.application.AppointmentReminderService;
import com.dentalclinic.appointment.application.AppointmentService;
import com.dentalclinic.appointment.application.ClaimedEmail;
import com.dentalclinic.appointment.application.CommunicationClaimService;
import com.dentalclinic.appointment.application.CommunicationDispatcher;
import com.dentalclinic.appointment.application.CommunicationEmailComposer;
import com.dentalclinic.appointment.application.EmailSendException;
import com.dentalclinic.appointment.application.EmailSender;
import com.dentalclinic.appointment.application.ReminderAuditRecorder;
import com.dentalclinic.clinic.application.ClinicService;
import com.dentalclinic.clinic.application.DentistService;
import com.dentalclinic.patient.application.PatientService;
import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.appointment.domain.Communication;
import com.dentalclinic.appointment.domain.CommunicationStatus;
import com.dentalclinic.appointment.domain.CommunicationType;
import com.dentalclinic.appointment.domain.RecipientKind;
import com.dentalclinic.appointment.infrastructure.config.AppointmentReminderProperties;
import com.dentalclinic.appointment.infrastructure.config.CommunicationProperties;
import com.dentalclinic.appointment.infrastructure.repository.AppointmentRepository;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/** T4.2 sobre DDL real: SMTP falso y opt-in de entrega solo en objetos de prueba, sin otro contexto. */
class AppointmentReminderValidityIntegrationTest extends AppointmentIntegrationTestSupport {

    @Autowired private AppointmentReminderJob producer;
    @Autowired private AppointmentReminderService generation;
    @Autowired private AppointmentService appointments;
    @Autowired private CommunicationEmailComposer composer;
    @Autowired private CommunicationProperties communications;
    @Autowired private AppointmentReminderProperties reminderProperties;
    @Autowired private Clock clock;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RecordingEmailSender sender;
    @Autowired private ReminderAuditRecorder audit;
    @Autowired private ClinicService clinics;
    @Autowired private DentistService dentists;
    @Autowired private PatientService patients;

    private CommunicationClaimService delivery;
    private CommunicationDispatcher dispatcher;

    @BeforeEach
    void prepareDelivery() {
        TestTime.advance(Duration.between(TestTime.now(), at(MARTES, 8, 0)));
        sender.reset();
        delivery = claimService(appointmentRepository, clock);
        dispatcher = dispatcher(delivery, composer, sender, clock);
    }

    @Test
    void cancelacionAntesDeGenerarNoProduceRecordatorio() {
        Appointment appointment = seed();
        appointments.cancel(appointment.getId(), ownerActor());
        assertThat(producer.generateOnce()).isZero();
        assertThat(reminderRows(appointment)).isEmpty();
    }

    @Test
    void cancelacionDespuesDeEncolarAnulaSinBorrarHistoricosNiReintentar() {
        Appointment appointment = generated();
        Long id = reminderRows(appointment).getFirst().getId();
        long intentions = communicationIntentionRepository.count();
        appointments.cancel(appointment.getId(), ownerActor());
        assertObsolete(id, 0);
        assertThat(notificationRepository.findAll()).anySatisfy(notification ->
                assertThat(notification.getTitle()).isEqualTo("Recordatorio de tu cita"));
        assertThat(communicationIntentionRepository.count()).isGreaterThan(intentions);
        assertThat(dispatcher.dispatchOnce()).isEqualTo(3); // Solo CANCELLED, nunca REMINDER.
        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(sender.messages()).noneSatisfy(email ->
                assertThat(email.subject()).startsWith("Recordatorio de tu cita dental"));
        assertObsolete(id, 0);
    }

    @Test
    void reprogramacionAnulaVersionViejaYLaNuevaEsElegible() {
        Appointment appointment = generated();
        Communication old = reminderRows(appointment).getFirst();
        appointments.reschedule(appointment.getId(), ownerActor(), new AppointmentService.RescheduleData(
                at(MARTES, 11, 0), at(MARTES, 11, 15), 1));
        assertObsolete(old.getId(), 0);
        assertThat(producer.generateOnce()).isEqualTo(1);
        assertThat(producer.generateOnce()).isZero();
        List<Communication> rows = reminderRows(appointment);
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(Communication::getOccurrenceVersion).containsExactly(1, 2);
        assertThat(rows.getLast().getIdempotencyKey()).endsWith(":V2");
        assertThat(rows.getFirst().getIdempotencyKey()).isEqualTo(old.getIdempotencyKey());
        assertThat(dispatcher.dispatchOnce()).isEqualTo(4); // Tres RESCHEDULED y REMINDER V2.
        assertThat(reminderRows(appointment).getLast().getStatus()).isEqualTo(CommunicationStatus.SENT);
        assertObsolete(old.getId(), 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "NO_SHOW"})
    void terminalesAnulanEnLaTransaccionDelCambio(String terminal) {
        Appointment appointment = generated();
        Long id = reminderRows(appointment).getFirst().getId();
        if (terminal.equals("COMPLETED")) {
            appointments.complete(appointment.getId(), dentistActor(), appointment.getOccurrenceVersion(), java.util.UUID.randomUUID().toString());
        } else {
            TestTime.advance(Duration.ofHours(2));
            appointments.markNoShow(appointment.getId(), ownerActor());
        }
        assertObsolete(id, 0);
        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(sender.sentCount()).isZero();
    }

    @Test
    void rollbackDelCambioDeCitaTambienRevierteLaInvalidacion() {
        Appointment appointment = generated();
        Long id = reminderRows(appointment).getFirst().getId();
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            appointments.cancel(appointment.getId(), ownerActor());
            transaction.setRollbackOnly();
        });
        assertThat(row(id).getSupersededAt()).isNull();
        assertThat(appointmentRepository.findById(appointment.getId()).orElseThrow().getStatus().name())
                .isEqualTo("SCHEDULED");
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
    }

    @Test
    void barridoCaducaAunqueEntregaYSmtpEstenDeshabilitados() {
        Appointment appointment = generated();
        Communication reminder = reminderRows(appointment).getFirst();
        delivery.claim(TestTime.now(), 10, Duration.ofHours(4));
        TestTime.advance(Duration.ofHours(2)); // Límite exacto: startAt == now.
        assertThat(reminderProperties.emailDeliveryEnabled()).isFalse();
        assertThat(communications.email().enabled()).isFalse();
        assertThat(producer.generateOnce()).isZero();
        assertObsolete(reminder.getId(), 0);
        assertThat(notificationRepository.count()).isEqualTo(1);
        assertThat(communicationIntentionRepository.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"STARTED", "VERSION", "CANCELLED"})
    void claimDetectaInvalidezInclusoSiFaltaInvalidacionPrevia(String reason) {
        Appointment appointment = generated();
        Long id = reminderRows(appointment).getFirst().getId();
        switch (reason) {
            case "STARTED" -> TestTime.advance(Duration.ofHours(2));
            case "VERSION" -> jdbcTemplate.update("UPDATE appointment SET occurrence_version = 2 WHERE id = ?",
                    appointment.getId());
            case "CANCELLED" -> jdbcTemplate.update("UPDATE appointment SET status = 'CANCELLED' WHERE id = ?",
                    appointment.getId());
            default -> throw new IllegalArgumentException(reason);
        }
        assertThat(delivery.claim(TestTime.now(), 10, Duration.ofMinutes(5))).isEmpty();
        assertObsolete(id, 0);
        assertThat(dispatcher.dispatchOnce()).isZero();
    }

    @Test
    void comunicacionYaObsoletaNoSeReclamaNiConsumeIntentos() {
        Appointment appointment = generated();
        Long id = reminderRows(appointment).getFirst().getId();
        jdbcTemplate.update("UPDATE communication SET superseded_at = now() WHERE id = ?", id);
        assertThat(delivery.claim(TestTime.now(), 10, Duration.ofMinutes(5))).isEmpty();
        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(row(id).getAttempts()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "RESCHEDULED", "STARTED", "VERSION", "STATUS"})
    void invalidacionEntreClaimYSmtpNoConsumeIntentos(String change) {
        Appointment appointment = generated();
        ClaimedEmail claimed = reserve();
        assertThat(claimed.attempts()).isZero();
        switch (change) {
            case "CANCELLED" -> appointments.cancel(appointment.getId(), ownerActor());
            case "RESCHEDULED" -> appointments.reschedule(appointment.getId(), ownerActor(),
                    new AppointmentService.RescheduleData(at(MARTES, 11, 0), at(MARTES, 11, 15), 1));
            case "STARTED" -> TestTime.advance(Duration.ofHours(2));
            case "VERSION" -> jdbcTemplate.update("UPDATE appointment SET occurrence_version = 2 WHERE id = ?",
                    appointment.getId());
            case "STATUS" -> jdbcTemplate.update("UPDATE appointment SET status = 'CANCELLED' WHERE id = ?",
                    appointment.getId());
            default -> throw new IllegalArgumentException(change);
        }
        assertThat(delivery.prepareReminderSend(claimed)).isEmpty();
        assertObsolete(claimed.id(), 0);
    }

    @Test
    void dispatcherRevalidaDespuesDeComponerEInmediatamenteAntesDeSend() {
        Appointment appointment = generated();
        CommunicationEmailComposer changingComposer = spy(new CommunicationEmailComposer(
                appointmentRepository, clinics, patients, dentists, feedback, appointmentEventRepository,
                mock(com.dentalclinic.appointment.infrastructure.repository.AppointmentOccurrenceSnapshotRepository.class)));
        doAnswer(invocation -> {
            appointments.cancel(appointment.getId(), ownerActor());
            return invocation.callRealMethod();
        }).when(changingComposer).compose(any());
        assertThat(dispatcher(delivery, changingComposer, sender, clock).dispatchOnce()).isZero();
        assertThat(sender.sentCount()).isZero();
        assertObsolete(reminderRows(appointment).getFirst().getId(), 0);
    }

    @Test
    void citaAusenteEnChequeoFinalSeTrataComoObsoleta() {
        Appointment appointment = generated();
        ClaimedEmail claimed = reserve();
        AppointmentRepository missing = mock(AppointmentRepository.class);
        when(missing.findByIdForUpdate(appointment.getId())).thenReturn(Optional.empty());
        assertThat(claimService(missing, clock).prepareReminderSend(claimed)).isEmpty();
        assertObsolete(claimed.id(), 0);
    }

    @Test
    void chequeoFinalLeeClockDespuesDeEsperarPorElLock() throws Exception {
        Appointment appointment = generated();
        ClaimedEmail claimed = reserve();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch preparing = new CountDownLatch(1);
        AppointmentRepository waitingRepository = mock(AppointmentRepository.class);
        when(waitingRepository.findByIdForUpdate(appointment.getId())).thenAnswer(invocation -> {
            preparing.countDown();
            return appointmentRepository.findByIdForUpdate(appointment.getId());
        });
        CommunicationClaimService waitingService = claimService(waitingRepository, clock);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(transaction -> {
                        appointmentRepository.findByIdForUpdate(appointment.getId()).orElseThrow();
                        locked.countDown();
                        await(release);
                    }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Optional<ClaimedEmail>> prepared = pool.submit(() -> waitingService.prepareReminderSend(claimed));
            assertThat(preparing.await(10, TimeUnit.SECONDS)).isTrue();
            TestTime.advance(Duration.ofHours(2));
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(prepared.get(10, TimeUnit.SECONDS)).isEmpty();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertObsolete(claimed.id(), 0);
    }

    @Test
    void cancelacionDuranteSmtpNoBloqueaYElResultadoAceptadoSeConserva() throws Exception {
        Appointment appointment = generated();
        Long id = reminderRows(appointment).getFirst().getId();
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        EmailSender blocked = email -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            sending.countDown();
            await(release);
            sender.send(email);
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> send = pool.submit(() -> dispatcher(delivery, composer, blocked, clock).dispatchOnce());
            assertThat(sending.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> cancel = pool.submit(() -> appointments.cancel(appointment.getId(), ownerActor()));
            cancel.get(10, TimeUnit.SECONDS); // La red bloqueada no retiene ni transacción ni locks.
            assertObsolete(id, 1);
            release.countDown();
            assertThat(send.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        Communication accepted = row(id);
        assertThat(accepted.getStatus()).isEqualTo(CommunicationStatus.SENT);
        assertThat(accepted.getSentAt()).isEqualTo(TestTime.now());
        assertThat(accepted.getSupersededAt()).isNotNull();
        assertThat(accepted.getNextRetryAt()).isNull();
        assertThat(accepted.getLastError()).isNull();
        delivery.recordReminderSent(new ClaimedEmail(id, accepted.getAddress(), CommunicationType.REMINDER,
                1, 1, appointment.getId(), null), TestTime.now().plusSeconds(10));
        assertThat(row(id).getSentAt()).isEqualTo(accepted.getSentAt());
    }

    @Test
    void falloSmtpTrasCancelacionRegistraErrorSinRetryNiFailed() {
        Appointment appointment = generated();
        EmailSender failAfterCancel = email -> {
            appointments.cancel(appointment.getId(), ownerActor());
            throw new EmailSendException("fallo SMTP falso después de cancelar");
        };
        assertThat(dispatcher(delivery, composer, failAfterCancel, clock).dispatchOnce()).isZero();
        Long id = reminderRows(appointment).getFirst().getId();
        assertObsolete(id, 1);
        assertThat(row(id).getLastError()).isEqualTo("EmailSendException");
        TestTime.advance(Duration.ofMinutes(10));
        dispatcher.dispatchOnce(); // Avisos normales de cancelación; no recordatorio.
        assertThat(row(id).getAttempts()).isEqualTo(1);
        assertThat(sender.messages()).noneSatisfy(email ->
                assertThat(email.subject()).startsWith("Recordatorio de tu cita dental"));
    }

    @Test
    void aceptacionAntesDeCancelarNoSeReescribeComoPendiente() {
        Appointment appointment = generated();
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        Long id = reminderRows(appointment).getFirst().getId();
        Instant sentAt = row(id).getSentAt();
        appointments.cancel(appointment.getId(), ownerActor());
        assertThat(row(id).getStatus()).isEqualTo(CommunicationStatus.SENT);
        assertThat(row(id).getSentAt()).isEqualTo(sentAt);
        assertThat(row(id).getSupersededAt()).isNull();
    }

    @Test
    void dosWorkersConcurrentesSoloEnvianUnaVezConLeaseVigente() throws Exception {
        Appointment appointment = generated();
        sender.setSendDelayMillis(200);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> { await(start); return dispatcher.dispatchOnce(); });
            Future<Integer> second = pool.submit(() -> { await(start); return dispatcher.dispatchOnce(); });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(sender.sentCount()).isEqualTo(1);
        assertThat(reminderRows(appointment).getFirst().getAttempts()).isEqualTo(1);
    }

    @Test
    void leaseVencidoRecuperaReservaSinConsumirIntentosYRechazaWorkerViejo() {
        Appointment appointment = generated();
        ClaimedEmail old = reserve();
        assertThat(delivery.claim(TestTime.now(), 10, Duration.ofMinutes(5))).isEmpty();
        TestTime.advance(Duration.ofMinutes(6));
        ClaimedEmail newer = reserve();
        assertThat(newer.leaseUntil()).isAfter(old.leaseUntil());
        assertThat(delivery.prepareReminderSend(old)).isEmpty();
        ClaimedEmail sending = delivery.prepareReminderSend(newer).orElseThrow();
        assertThat(sending.attempts()).isEqualTo(1);
        assertThat(delivery.prepareReminderSend(newer)).isEmpty(); // La misma reserva no inicia dos envíos.
        sender.send(composer.compose(sending));
        delivery.recordReminderSent(sending, TestTime.now());
        assertThat(reminderRows(appointment).getFirst().getStatus()).isEqualTo(CommunicationStatus.SENT);
    }

    @Test
    void falloDeWorkerAntiguoNoSobrescribeReservaNiResultadoPosterior() {
        Appointment appointment = generated();
        ClaimedEmail old = delivery.prepareReminderSend(reserve()).orElseThrow();
        TestTime.advance(Duration.ofMinutes(6));
        ClaimedEmail newReservation = reserve();
        // Mismo contador hasta iniciar SMTP: el lease diferencia ambas reservas.
        assertThat(delivery.recordReminderFailure(old, "fallo viejo", TestTime.now().plusSeconds(60),
                TestTime.now())).isFalse();
        assertThat(row(old.id()).getNextRetryAt()).isEqualTo(newReservation.leaseUntil());
        ClaimedEmail newer = delivery.prepareReminderSend(newReservation).orElseThrow();
        assertThat(newer.attempts()).isEqualTo(2);
        delivery.recordReminderSent(newer, TestTime.now());
        assertThat(delivery.recordReminderFailure(old, "fallo viejo", TestTime.now().plusSeconds(60),
                TestTime.now())).isFalse();
        assertThat(reminderRows(appointment).getFirst().getStatus()).isEqualTo(CommunicationStatus.SENT);
        assertThat(row(old.id()).getLastError()).isNull();
    }

    @Test
    void aceptacionTardiaEsRealAunqueUnWorkerPosteriorHayaFallado() {
        Appointment appointment = generated();
        jdbcTemplate.update("UPDATE communication SET attempts = 2 WHERE id = ?",
                reminderRows(appointment).getFirst().getId()); // Dos intentos previos; siguientes = 3 y 4.
        ClaimedEmail old = delivery.prepareReminderSend(reserve()).orElseThrow();
        TestTime.advance(Duration.ofMinutes(6));
        ClaimedEmail newer = delivery.prepareReminderSend(reserve()).orElseThrow();
        assertThat(delivery.recordReminderFailure(newer, "fallo falso", TestTime.now(),
                TestTime.now())).isTrue();
        assertThat(row(newer.id()).getStatus()).isEqualTo(CommunicationStatus.FAILED);
        delivery.recordReminderSent(old, TestTime.now());
        assertThat(reminderRows(appointment).getFirst().getStatus()).isEqualTo(CommunicationStatus.SENT);
    }

    @Test
    void reinicioRecuperaPendientesPeroNuncaCaducados() {
        Appointment appointment = generated();
        reserve();
        TestTime.advance(Duration.ofMinutes(6));
        CommunicationClaimService restarted = claimService(appointmentRepository, clock);
        assertThat(dispatcher(restarted, composer, sender, clock).dispatchOnce()).isEqualTo(1);
        assertThat(dispatcher(restarted, composer, sender, clock).dispatchOnce()).isZero();
        assertThat(reminderRows(appointment).getFirst().getAttempts()).isEqualTo(1);

        Appointment later = seed(at(MARTES, 11, 0));
        assertThat(producer.generateOnce()).isEqualTo(1);
        restarted.claim(TestTime.now(), 10, Duration.ofHours(5));
        TestTime.advance(Duration.ofHours(3));
        assertThat(dispatcher(claimService(appointmentRepository, clock), composer, sender, clock)
                .dispatchOnce()).isZero();
        assertObsolete(reminderRows(later).getFirst().getId(), 0);
    }

    @Test
    void retryVigenteFuncionaPeroCaducidadDuranteBackoffImpideNuevoIntento() {
        Appointment appointment = generated();
        sender.alwaysFail();
        assertThat(dispatcher.dispatchOnce()).isZero();
        Communication email = reminderRows(appointment).getFirst();
        assertThat(email.getAttempts()).isEqualTo(1);
        assertThat(email.getStatus()).isEqualTo(CommunicationStatus.PENDING);
        assertThat(email.getNextRetryAt()).isAfter(TestTime.now());
        TestTime.advance(Duration.ofHours(2));
        sender.succeed();
        assertThat(dispatcher.dispatchOnce()).isZero();
        assertObsolete(email.getId(), 1);
    }

    @Test
    void reintentoDeRecordatorioVigentePuedeCompletar() {
        Appointment appointment = generated();
        sender.alwaysFail();
        assertThat(dispatcher.dispatchOnce()).isZero();
        TestTime.advance(Duration.ofMinutes(2));
        sender.succeed();
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(reminderRows(appointment).getFirst().getAttempts()).isEqualTo(2);
        assertThat(reminderRows(appointment).getFirst().getStatus()).isEqualTo(CommunicationStatus.SENT);
    }

    @ParameterizedTest
    @EnumSource(value = CommunicationType.class, names = {"CREATED", "RESCHEDULED", "CANCELLED"})
    void avisos8CDeCitaCanceladaNoSePresentanComoVigentes(CommunicationType type) {
        Appointment appointment = seed();
        appointment.cancel(TestTime.now());
        appointmentRepository.saveAndFlush(appointment);
        Communication email = communicationRepository.saveAndFlush(Communication.create(appointment.getId(),
                type, RecipientKind.USER, null, patientUser.getId(), patientUser.getEmail(),
                "APPOINTMENT:" + appointment.getId() + ":" + type + ":EMAIL:U" + patientUser.getId()
                        + (type == CommunicationType.RESCHEDULED ? ":V1" : ""), 1));
        TestTime.advance(Duration.ofHours(3));
        if (type == CommunicationType.CANCELLED) {
            assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
            assertThat(row(email.getId()).getStatus()).isEqualTo(CommunicationStatus.SENT);
            assertThat(row(email.getId()).getSupersededAt()).isNull();
        } else {
            assertThat(dispatcher.dispatchOnce()).isZero();
            assertThat(row(email.getId()).getStatus()).isEqualTo(CommunicationStatus.PENDING);
            assertThat(row(email.getId()).getSupersededAt()).isNotNull();
        }
        assertThat(row(email.getId()).getAttempts()).isEqualTo(1);
    }

    @Test
    void leaseConNanosegundosSobreviveALaPrecisionDePostgresql() {
        Appointment appointment = generated();
        Clock precise = Clock.fixed(TestTime.now().plusNanos(123), ZoneOffset.UTC);
        CommunicationClaimService preciseService = claimService(appointmentRepository, precise);
        ClaimedEmail reservation = preciseService.claim(precise.instant(), 10, Duration.ofMinutes(5)).getFirst();
        assertThat(reservation.leaseUntil()).isEqualTo(reservation.leaseUntil().truncatedTo(ChronoUnit.MICROS));
        assertThat(preciseService.prepareReminderSend(reservation)).isPresent();
        assertThat(row(reminderRows(appointment).getFirst().getId()).getAttempts()).isEqualTo(1);
    }

    @Test
    void cancelacionYClaimConcurrentesTerminanSinDeadlockNiRecordatorioVigente() throws Exception {
        Appointment appointment = generated();
        Long id = reminderRows(appointment).getFirst().getId();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> cancel = pool.submit(() -> { await(start); appointments.cancel(appointment.getId(), ownerActor()); });
            Future<List<ClaimedEmail>> claim = pool.submit(() -> {
                await(start);
                return delivery.claim(TestTime.now(), 10, Duration.ofMinutes(5));
            });
            start.countDown();
            cancel.get(10, TimeUnit.SECONDS);
            for (ClaimedEmail email : claim.get(10, TimeUnit.SECONDS)) {
                if (email.type() == CommunicationType.REMINDER) {
                    assertThat(delivery.prepareReminderSend(email)).isEmpty();
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertObsolete(id, 0);
    }

    @Test
    void generacionYCancelacionCompartenLockDeCita() throws Exception {
        Appointment appointment = seed();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> generate = pool.submit(() -> {
                await(start);
                return generation.generate(appointment.getId(), 1);
            });
            Future<?> cancel = pool.submit(() -> { await(start); appointments.cancel(appointment.getId(), ownerActor()); });
            start.countDown();
            generate.get(10, TimeUnit.SECONDS);
            cancel.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(reminderRows(appointment)).hasSizeLessThanOrEqualTo(1).allSatisfy(email ->
                assertThat(email.getSupersededAt()).isNotNull());
        assertThat(producer.generateOnce()).isZero();
    }

    private Appointment seed() {
        return seed(at(MARTES, 10, 0));
    }

    private Appointment seed(Instant start) {
        return appointmentRepository.saveAndFlush(Appointment.create(patient.getId(), dentist.getId(), owner.getId(),
                start, start.plus(Duration.ofMinutes(15)), 15, null, null));
    }

    private Appointment generated() {
        Appointment appointment = seed();
        assertThat(producer.generateOnce()).isEqualTo(1);
        return appointment;
    }

    private ClaimedEmail reserve() {
        List<ClaimedEmail> rows = delivery.claim(TestTime.now(), 10, Duration.ofMinutes(5));
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private List<Communication> reminderRows(Appointment appointment) {
        return communicationRepository.findAll().stream()
                .filter(email -> email.getAppointmentId().equals(appointment.getId())
                        && email.getType() == CommunicationType.REMINDER)
                .sorted(java.util.Comparator.comparing(Communication::getId)).toList();
    }

    private Communication row(Long id) {
        return communicationRepository.findById(id).orElseThrow();
    }

    private void assertObsolete(Long id, int attempts) {
        Communication email = row(id);
        assertThat(email.getSupersededAt()).isNotNull();
        assertThat(email.getStatus()).isEqualTo(CommunicationStatus.PENDING);
        assertThat(email.getAttempts()).isEqualTo(attempts);
        assertThat(email.getNextRetryAt()).isNull();
    }

    private AuthenticatedUser ownerActor() {
        return new AuthenticatedUser(owner.getId(), Role.OWNER);
    }

    private AuthenticatedUser dentistActor() {
        return new AuthenticatedUser(dentistUser.getId(), Role.DENTIST);
    }

    private CommunicationClaimService claimService(AppointmentRepository repository, Clock sourceClock) {
        CommunicationClaimService target = new CommunicationClaimService(communicationRepository,
                new AppointmentReminderProperties(false, 50, 10, null, null, true), repository, sourceClock,
                audit, communications);
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (CommunicationClaimService) factory.getProxy();
    }

    private CommunicationDispatcher dispatcher(CommunicationClaimService claims,
            CommunicationEmailComposer emailComposer, EmailSender emailSender, Clock sourceClock) {
        return new CommunicationDispatcher(claims, emailComposer, communications, Optional.of(emailSender), sourceClock, feedback);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timeout de coordinación de prueba");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}

package com.dentalclinic.appointment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import jakarta.validation.Validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import com.dentalclinic.appointment.application.AppointmentCommunicationPlanner;
import com.dentalclinic.appointment.application.AppointmentReminderJob;
import com.dentalclinic.appointment.application.AppointmentReminderService;
import com.dentalclinic.appointment.application.CommunicationClaimService;
import com.dentalclinic.appointment.application.CommunicationDispatcher;
import com.dentalclinic.appointment.application.CommunicationEmailComposer;
import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.appointment.domain.Communication;
import com.dentalclinic.appointment.domain.CommunicationIntention;
import com.dentalclinic.appointment.domain.CommunicationIntention.Channel;
import com.dentalclinic.appointment.domain.CommunicationIntention.PartyKind;
import com.dentalclinic.appointment.domain.CommunicationIntention.RequiredRole;
import com.dentalclinic.appointment.domain.CommunicationType;
import com.dentalclinic.appointment.domain.RecipientKind;
import com.dentalclinic.appointment.infrastructure.config.AppointmentReminderProperties;
import com.dentalclinic.appointment.infrastructure.config.CommunicationProperties;
import com.dentalclinic.appointment.infrastructure.repository.AppointmentRepository;
import com.dentalclinic.appointment.infrastructure.repository.AppointmentRepository.ReminderCandidate;
import com.dentalclinic.appointment.infrastructure.repository.CommunicationIntentionRepository;
import com.dentalclinic.clinic.application.ClinicService;
import com.dentalclinic.clinic.application.DentistService;
import com.dentalclinic.clinic.domain.Clinic;
import com.dentalclinic.clinic.domain.Dentist;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.application.UserService;
import com.dentalclinic.patient.application.PatientService;
import com.dentalclinic.patient.domain.Patient;

/** T4.1: PostgreSQL efímero con DDL real, reloj determinista y ningún emisor SMTP real. */
class AppointmentReminderIntegrationTest extends AppointmentIntegrationTestSupport {

    @Autowired private AppointmentReminderJob job;
    @Autowired private AppointmentReminderService reminders;
    @Autowired private AppointmentCommunicationPlanner planner;
    @Autowired private CommunicationDispatcher dispatcher;
    @Autowired private CommunicationClaimService claims;
    @Autowired private CommunicationEmailComposer composer;
    @Autowired private CommunicationProperties communicationProperties;
    @Autowired private AppointmentReminderProperties reminderProperties;
    @Autowired private PatientService patients;
    @Autowired private ClinicService clinics;
    @Autowired private DentistService dentists;
    @Autowired private UserService users;
    @Autowired private Clock clock;
    @Autowired private Validator validator;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RecordingEmailSender sender;

    @BeforeEach
    void resetSender() {
        sender.reset();
    }

    static Stream<Arguments> windowCases() {
        return Stream.of(
                Arguments.of(Duration.ofHours(5).plusNanos(1_000_000), 0),
                Arguments.of(Duration.ofHours(5), 1),
                Arguments.of(Duration.ofHours(4), 1),
                Arguments.of(Duration.ofSeconds(1), 1),
                Arguments.of(Duration.ZERO, 0),
                Arguments.of(Duration.ofMinutes(-1), 0));
    }

    @ParameterizedTest
    @MethodSource("windowCases")
    void respetaLaVentanaExacta(Duration advance, int expected) {
        Appointment appointment = seed(patient, TestTime.now().plus(advance));
        assertThat(job.generateOnce()).isEqualTo(expected);
        assertThat(reminderCount(appointment)).isEqualTo(expected);
        assertThat(notificationCount(appointment)).isEqualTo(expected);
        assertThat(intentionCount(appointment)).isEqualTo(expected * 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "COMPLETED", "NO_SHOW"})
    void losEstadosTerminalesNoGeneran(String status) {
        Appointment appointment = Appointment.create(patient.getId(), dentist.getId(), owner.getId(),
                TestTime.now().plus(Duration.ofHours(4)), TestTime.now().plus(Duration.ofHours(5)),
                60, null, null);
        switch (status) {
            case "CANCELLED" -> appointment.cancel(TestTime.now());
            case "COMPLETED" -> appointment.complete(TestTime.now());
            case "NO_SHOW" -> appointment.markNoShow(TestTime.now());
            default -> throw new IllegalArgumentException(status);
        }
        appointmentRepository.saveAndFlush(appointment);
        assertThat(job.generateOnce()).isZero();
        assertThat(intentionCount(appointment)).isZero();
    }

    @Test
    void cuentaConCorreoValidoUsaIdentidadUsuarioYDosCanales() {
        patient.update(patient.getFirstName(), patient.getLastName(), "otro@dc.test", patient.getPhone(),
                null, null, null, TestTime.now());
        patientRepository.saveAndFlush(patient);
        Appointment appointment = seed(patient, inHours(4));

        assertThat(job.generateOnce()).isEqualTo(1);
        Communication email = onlyReminder(appointment);
        assertThat(email.getRecipientKind()).isEqualTo(RecipientKind.USER);
        assertThat(email.getRecipientUserId()).isEqualTo(patientUser.getId());
        assertThat(email.getAddress()).isEqualTo(patientUser.getEmail());
        assertThat(email.getIdempotencyKey()).isEqualTo(key(appointment, "U" + patientUser.getId()));
        assertThat(notificationRepository.findAll()).singleElement().satisfies(notification -> {
            assertThat(notification.getUserId()).isEqualTo(patientUser.getId());
            assertThat(notification.getAppointmentId()).isEqualTo(appointment.getId());
            assertThat(notification.isRead()).isFalse();
        });
        assertThat(intentionCount(appointment)).isEqualTo(2);
    }

    @Test
    void sinCuentaConCorreoSoloGeneraEmail() {
        Patient administrative = patientRepository.saveAndFlush(
                Patient.create("Solo", "Correo", "solo@dc.test", null, null, null));
        Appointment appointment = seed(administrative, inHours(4));
        assertThat(job.generateOnce()).isEqualTo(1);
        Communication email = onlyReminder(appointment);
        assertThat(email.getRecipientKind()).isEqualTo(RecipientKind.PATIENT);
        assertThat(email.getRecipientPatientId()).isEqualTo(administrative.getId());
        assertThat(email.getAddress()).isEqualTo("solo@dc.test");
        assertThat(email.getIdempotencyKey()).isEqualTo(key(appointment, administrative.getId().toString()));
        assertThat(notificationCount(appointment)).isZero();
        assertNoContact(appointment, "IN_APP");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "correo-invalido", "   "})
    void cuentaSinCorreoValidoMantieneNotificacion(String invalidEmail) {
        // User.email es NOT NULL e inmutable en JPA; simular datos históricos inválidos solo en test.
        jdbcTemplate.update("UPDATE users SET email = ? WHERE id = ?", invalidEmail, patientUser.getId());
        patient.update(patient.getFirstName(), patient.getLastName(), null, patient.getPhone(),
                null, null, null, TestTime.now());
        patientRepository.saveAndFlush(patient);
        Appointment appointment = seed(patient, inHours(4));

        assertThat(job.generateOnce()).isEqualTo(1);
        assertThat(reminderCount(appointment)).isZero();
        assertThat(notificationCount(appointment)).isEqualTo(1);
        assertNoContact(appointment, "EMAIL");
        assertThat(job.generateOnce()).isZero();
        assertThat(notificationCount(appointment)).isEqualTo(1);
    }

    @Test
    void correoUsuarioInvalidoPuedeUsarContactoPacienteSinInferirCuenta() {
        jdbcTemplate.update("UPDATE users SET email = 'invalido' WHERE id = ?", patientUser.getId());
        Appointment appointment = seed(patient, inHours(4));
        assertThat(job.generateOnce()).isEqualTo(1);
        Communication email = onlyReminder(appointment);
        assertThat(email.getRecipientKind()).isEqualTo(RecipientKind.PATIENT);
        assertThat(email.getRecipientPatientId()).isEqualTo(patient.getId());
        assertThat(email.getIdempotencyKey()).isEqualTo(key(appointment, patient.getId().toString()));
        assertThat(notificationCount(appointment)).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("invalidAdministrativeEmails")
    void sinCuentaNiCorreoValidoRegistraAmbasAusencias(String email) {
        Patient noChannel = patientRepository.saveAndFlush(
                Patient.create("Sin", "Canal", email, null, null, null));
        Appointment appointment = seed(noChannel, inHours(4));
        assertThat(job.generateOnce()).isEqualTo(1);
        assertThat(reminderCount(appointment)).isZero();
        assertThat(notificationCount(appointment)).isZero();
        assertThat(intentionCount(appointment)).isEqualTo(2);
        assertNoContact(appointment, "EMAIL");
        assertNoContact(appointment, "IN_APP");
        assertThat(job.generateOnce()).isZero();
    }

    static Stream<Arguments> invalidAdministrativeEmails() {
        return Stream.of(Arguments.of((String) null), Arguments.of(""), Arguments.of("mal-formato"));
    }

    @Test
    void repetirYReiniciarLogicamenteNoDuplica() {
        Appointment appointment = seed(patient, inHours(4));
        assertThat(job.generateOnce()).isEqualTo(1);
        assertThat(job.generateOnce()).isZero();
        AppointmentReminderJob restarted = new AppointmentReminderJob(
                appointmentRepository, reminders, clock, reminderProperties, claims);
        assertThat(restarted.generateOnce()).isZero();
        assertThat(reminderCount(appointment)).isEqualTo(1);
        assertThat(notificationCount(appointment)).isEqualTo(1);
        assertThat(intentionCount(appointment)).isEqualTo(2);
        assertThat(reminders.generate(appointment.getId(), 1)).isFalse();
    }

    @Test
    void dosProductoresConcurrentesMaterializanUnaSolaVez() throws Exception {
        Appointment appointment = seed(patient, inHours(4));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> { start.await(); return job.generateOnce(); });
            Future<Integer> second = pool.submit(() -> { start.await(); return job.generateOnce(); });
            start.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS) + second.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(reminderCount(appointment)).isEqualTo(1);
        assertThat(notificationCount(appointment)).isEqualTo(1);
        assertThat(intentionCount(appointment)).isEqualTo(2);
    }

    @Test
    void elWorkerRevalidaVersionEstadoYFechaDespuesDeSeleccionar() {
        Appointment appointment = seed(patient, inHours(4));
        ReminderCandidate selected = appointmentRepository.findReminderCandidates(
                TestTime.now(), inHours(5), 0, 10).getFirst();
        appointment.reschedule(inHours(3), inHours(3).plus(Duration.ofMinutes(15)), 15, TestTime.now());
        appointmentRepository.saveAndFlush(appointment);
        assertThat(reminders.generate(selected.getId(), selected.getOccurrenceVersion())).isFalse();
        assertThat(job.generateOnce()).isEqualTo(1);
        assertThat(onlyReminder(appointment).getOccurrenceVersion()).isEqualTo(2);

        Appointment movedOut = seed(patient, inHours(4));
        movedOut.reschedule(inHours(6), inHours(6).plus(Duration.ofMinutes(15)), 15, TestTime.now());
        appointmentRepository.saveAndFlush(movedOut);
        assertThat(reminders.generate(movedOut.getId(), 2)).isFalse();
        movedOut.cancel(TestTime.now());
        appointmentRepository.saveAndFlush(movedOut);
        assertThat(reminders.generate(movedOut.getId(), 2)).isFalse();
        assertThat(reminderCount(movedOut)).isZero();
    }

    @Test
    void alEntrarEnLaVentanaTrasUnaParadaSeRecupera() {
        Appointment appointment = seed(patient, inHours(6));
        assertThat(job.generateOnce()).isZero();
        TestTime.advance(Duration.ofHours(2));
        assertThat(new AppointmentReminderJob(appointmentRepository, reminders, clock,
                reminderProperties, claims).generateOnce()).isEqualTo(1);
        assertThat(notificationCount(appointment)).isEqualTo(1);
    }

    @Test
    void smtpDeshabilitadoNoImpideNotificacionNiConsumeIntentos() {
        Appointment appointment = seed(patient, inHours(4));
        assertThat(communicationProperties.email().enabled()).isFalse();
        assertThat(job.generateOnce()).isEqualTo(1);
        CommunicationDispatcher withoutSmtp = new CommunicationDispatcher(claims, composer,
                communicationProperties, Optional.empty(), clock, feedback);
        assertThat(withoutSmtp.dispatchOnce()).isZero();
        assertThat(notificationCount(appointment)).isEqualTo(1);
        assertPendingWithoutAttempts(appointment);
    }

    @Test
    void barreraDeEnvioExcluyeReminderSinBloquearOtrosEmails() {
        Appointment appointment = seed(patient, inHours(4));
        assertThat(job.generateOnce()).isEqualTo(1);
        communicationRepository.saveAndFlush(Communication.create(appointment.getId(),
                CommunicationType.CREATED, RecipientKind.USER, null, patientUser.getId(),
                patientUser.getEmail(), "APPOINTMENT:" + appointment.getId() + ":CREATED:EMAIL:U"
                        + patientUser.getId(), 1));
        assertThat(reminderProperties.emailDeliveryEnabled()).isFalse();
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(sender.messages()).singleElement().satisfies(email ->
                assertThat(email.subject()).isEqualTo("Cita registrada — Clínica Dental"));
        assertThat(dispatcher.dispatchOnce()).isZero();
        assertPendingWithoutAttempts(appointment);
    }

    @Test
    void lotesAcotadosSinSaltosAlCambiarElConjuntoDePendientes() {
        seed(patient, inHours(3));
        seed(patient, inHours(3).plus(Duration.ofMinutes(20)));
        seed(patient, inHours(3).plus(Duration.ofMinutes(40)));
        AppointmentReminderJob bounded = new AppointmentReminderJob(appointmentRepository, reminders,
                clock, properties(1, 2), claims);
        assertThat(bounded.generateOnce()).isEqualTo(2);
        assertThat(communicationRepository.count()).isEqualTo(2);
        assertThat(bounded.generateOnce()).isEqualTo(1);
        assertThat(bounded.generateOnce()).isZero();
        assertThat(notificationRepository.count()).isEqualTo(3);
    }

    @Test
    void falloTrasMaterializarHaceRollbackYNoDetieneLaSiguienteCita() {
        Appointment bad = seed(patient, inHours(3));
        Appointment good = seed(patient, inHours(4));
        AppointmentCommunicationPlanner failingPlanner = spy(new AppointmentCommunicationPlanner(
                communicationRepository, notificationRepository, communicationIntentionRepository,
                users, validator, appointmentEventRepository,
                org.mockito.Mockito.mock(com.dentalclinic.patient.application.PatientService.class)));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (((Appointment) invocation.getArgument(0)).getId().equals(bad.getId())) {
                throw new IllegalStateException("Fallo aislado después de escribir ambos canales");
            }
            return null;
        }).when(failingPlanner).reminder(any(), any(), any(Clinic.class), any(Dentist.class));
        AppointmentReminderService worker = transactionalWorker(communicationIntentionRepository, failingPlanner);
        assertThat(new AppointmentReminderJob(appointmentRepository, worker, clock,
                properties(1, 10), claims).generateOnce()).isEqualTo(1);
        assertThat(reminderCount(bad)).isZero();
        assertThat(notificationCount(bad)).isZero();
        assertThat(intentionCount(bad)).isZero();
        assertThat(reminderCount(good)).isEqualTo(1);
        assertThat(notificationCount(good)).isEqualTo(1);
        assertThat(job.generateOnce()).isEqualTo(1); // El fallo no consumió la ocurrencia.
    }

    @Test
    void colisionDeCommunicationKeyNoDetieneElLote() {
        Appointment collision = seed(patient, inHours(3));
        Appointment good = seed(patient, inHours(4));
        communicationRepository.saveAndFlush(Communication.create(collision.getId(),
                CommunicationType.REMINDER, RecipientKind.USER, null, patientUser.getId(),
                patientUser.getEmail(), key(collision, "U" + patientUser.getId()), 1));
        assertThat(job.generateOnce()).isEqualTo(1);
        assertThat(reminderCount(collision)).isEqualTo(1);
        assertThat(notificationCount(collision)).isZero();
        assertThat(intentionCount(collision)).isZero();
        assertThat(notificationCount(good)).isEqualTo(1);
    }

    @Test
    void colisionDeCiUniqRevierteAmbosCanalesYContinuaFueraDeLaTransaccion() {
        Appointment collision = seed(patient, inHours(3));
        Appointment good = seed(patient, inHours(4));
        communicationIntentionRepository.saveAndFlush(CommunicationIntention.noContact(
                collision.getId(), CommunicationType.REMINDER, 1, Channel.IN_APP,
                RequiredRole.PATIENT, PartyKind.PATIENT, patient.getId()));
        // Simular selección y precheck anteriores al commit de otro productor.
        AppointmentRepository staleSelection = mock(AppointmentRepository.class);
        when(staleSelection.findReminderCandidates(any(), any(), anyLong(), anyInt()))
                .thenReturn(List.of(candidate(collision), candidate(good)));
        CommunicationIntentionRepository staleCheck = mock(CommunicationIntentionRepository.class);
        AppointmentReminderService worker = transactionalWorker(staleCheck, planner);
        assertThat(new AppointmentReminderJob(staleSelection, worker, clock,
                properties(10, 1), claims).generateOnce()).isEqualTo(1);
        assertThat(reminderCount(collision)).isZero();
        assertThat(notificationCount(collision)).isZero();
        assertThat(intentionCount(collision)).isEqualTo(1); // Solo la intención preexistente.
        assertThat(reminderCount(good)).isEqualTo(1);
        assertThat(notificationCount(good)).isEqualTo(1);
    }

    @Test
    void transaccionIndependienteSobreviveAlRollbackDelLlamador() {
        Appointment appointment = seed(patient, inHours(4));
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            assertThat(reminders.generate(appointment.getId(), 1)).isTrue();
            transaction.setRollbackOnly();
        });
        assertThat(reminderCount(appointment)).isEqualTo(1);
        assertThat(notificationCount(appointment)).isEqualTo(1);
        assertThat(intentionCount(appointment)).isEqualTo(2);
    }

    @Test
    void fechaDeNotificacionUsaZonaDeClinica() {
        setTimezone("America/Mexico_City");
        Appointment appointment = seed(patient, inHours(5)); // 13:00Z -> 07:00 local.
        assertThat(job.generateOnce()).isEqualTo(1);
        assertThat(notificationRepository.findAll()).singleElement().satisfies(notification ->
                assertThat(notification.getBody()).contains("07:00"));
        assertThat(intentionCount(appointment)).isEqualTo(2);
    }

    private Appointment seed(Patient recipient, Instant start) {
        return appointmentRepository.saveAndFlush(Appointment.create(recipient.getId(), dentist.getId(),
                owner.getId(), start, start.plus(Duration.ofMinutes(15)), 15, null, null));
    }

    private Instant inHours(int hours) {
        return TestTime.now().plus(Duration.ofHours(hours));
    }

    private long reminderCount(Appointment appointment) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM communication "
                + "WHERE appointment_id = ? AND type = 'REMINDER'", Long.class, appointment.getId());
    }

    private long notificationCount(Appointment appointment) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM notification WHERE appointment_id = ?",
                Long.class, appointment.getId());
    }

    private long intentionCount(Appointment appointment) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM communication_intention "
                + "WHERE appointment_id = ? AND event_type = 'REMINDER'", Long.class, appointment.getId());
    }

    private Communication onlyReminder(Appointment appointment) {
        return communicationRepository.findAll().stream()
                .filter(email -> email.getAppointmentId().equals(appointment.getId())
                        && email.getType() == CommunicationType.REMINDER)
                .findFirst().orElseThrow();
    }

    private String key(Appointment appointment, String recipient) {
        return "APPOINTMENT:" + appointment.getId() + ":REMINDER:EMAIL:" + recipient
                + ":V" + appointment.getOccurrenceVersion();
    }

    private void assertNoContact(Appointment appointment, String channel) {
        assertThat(jdbcTemplate.queryForMap("SELECT resolution, communication_id, notification_id, address "
                + "FROM communication_intention WHERE appointment_id = ? AND event_type = 'REMINDER' "
                + "AND channel = ?", appointment.getId(), channel))
                .containsEntry("resolution", "NO_CONTACT_CHANNEL")
                .containsEntry("communication_id", null).containsEntry("notification_id", null)
                .containsEntry("address", null);
    }

    private void assertPendingWithoutAttempts(Appointment appointment) {
        Communication email = onlyReminder(appointment);
        assertThat(email.getStatus().name()).isEqualTo("PENDING");
        assertThat(email.getAttempts()).isZero();
        assertThat(email.getNextRetryAt()).isNull();
    }

    private AppointmentReminderProperties properties(int batchSize, int maxBatches) {
        return new AppointmentReminderProperties(false, batchSize, maxBatches, null, null, false);
    }

    private ReminderCandidate candidate(Appointment appointment) {
        return new ReminderCandidate() {
            public Long getId() { return appointment.getId(); }
            public int getOccurrenceVersion() { return appointment.getOccurrenceVersion(); }
        };
    }

    /** Inyecta fallos en el planner conservando el mismo interceptor REQUIRES_NEW de producción. */
    private AppointmentReminderService transactionalWorker(CommunicationIntentionRepository intentions,
            AppointmentCommunicationPlanner communicationPlanner) {
        AppointmentReminderService target = new AppointmentReminderService(appointmentRepository,
                intentions, patients, clinics, communicationPlanner, clock, dentists);
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        return (AppointmentReminderService) factory.getProxy();
    }
}

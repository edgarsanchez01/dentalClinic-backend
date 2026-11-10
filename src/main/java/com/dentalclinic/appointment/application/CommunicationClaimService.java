package com.dentalclinic.appointment.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.appointment.domain.Communication;
import com.dentalclinic.appointment.domain.CommunicationStatus;
import com.dentalclinic.appointment.domain.CommunicationType;
import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.appointment.domain.AppointmentStatus;
import com.dentalclinic.appointment.domain.AppointmentEventType;
import com.dentalclinic.appointment.infrastructure.config.AppointmentReminderProperties;
import com.dentalclinic.appointment.infrastructure.config.CommunicationProperties;
import com.dentalclinic.appointment.infrastructure.repository.CommunicationRepository;
import com.dentalclinic.appointment.infrastructure.repository.AppointmentRepository;

/**
 * Persistencia transaccional del claim y del resultado de entrega (F8/T1).
 *
 * <p>Cada método abre una <strong>transacción corta</strong>. En particular, {@link #claim} bloquea
 * las filas elegibles con {@code FOR UPDATE SKIP LOCKED} y las reserva con un lease; la transacción
 * termina antes de que el dispatcher abra SMTP (Architecture.md 9.1/9.4, PRD §34.13). Marcar
 * {@code SENT}/retry/FAILED son operaciones separadas y pequeñas, nunca envueltas alrededor del
 * envío de red.
 */
@Service
public class CommunicationClaimService {

    private final CommunicationRepository repository;
    private final AppointmentReminderProperties reminders;
    private final AppointmentRepository appointments;
    private final Clock clock;
    private final ReminderAuditRecorder audit;
    private final CommunicationProperties communications;

    public CommunicationClaimService(CommunicationRepository repository,
            AppointmentReminderProperties reminders, AppointmentRepository appointments, Clock clock,
            ReminderAuditRecorder audit, CommunicationProperties communications) {
        this.repository = repository;
        this.reminders = reminders;
        this.appointments = appointments;
        this.clock = clock;
        this.audit = audit;
        this.communications = communications;
    }

    /**
     * Reclama hasta {@code batchSize} filas elegibles y las reserva con un lease. Devolverlas como
     * {@link ClaimedEmail} desacopla el envío posterior de la sesión de persistencia.
     */
    @Transactional
    public List<ClaimedEmail> claim(Instant now, int batchSize, Duration lease) {
        invalidateStale(now, batchSize);
        // La entrega de REMINDER sigue requiriendo activación explícita (default false).
        List<Long> ids = repository.lockClaimable(now, batchSize, reminders.emailDeliveryEnabled());
        if (ids.isEmpty()) {
            return List.of();
        }
        // TIMESTAMPTZ tiene precisión de microsegundos: el token debe sobrevivir al round-trip a BD.
        Instant leaseUntil = now.plus(lease).truncatedTo(ChronoUnit.MICROS);
        List<ClaimedEmail> claimed = new ArrayList<>(ids.size());
        for (Communication communication : repository.findAllById(ids)) {
            if (communication.getStatus() != CommunicationStatus.PENDING
                    || communication.getSupersededAt() != null) {
                continue;
            }
            if (communication.getType() == CommunicationType.REMINDER) {
                // No adquirir un lock de appointment con communication ya bloqueada: lectura MVCC.
                Instant currentNow = clock.instant();
                if (!isCurrent(communication, appointments.findById(communication.getAppointmentId())
                        .orElse(null), currentNow)) {
                    communication.markSuperseded(currentNow);
                    continue;
                }
                communication.reserveReminder(leaseUntil, now);
            } else {
                communication.markClaimStarted(leaseUntil, now);
            }
            claimed.add(snapshot(communication, leaseUntil));
        }
        return claimed;
    }

    /** Invocado también por el productor, aunque SMTP y la entrega de REMINDER estén apagados. */
    @Transactional
    public int supersedeInvalidReminders(Instant now, int batchSize) {
        return invalidateStale(now, batchSize);
    }

    /** Último chequeo, en transacción corta; termina antes de llamar a EmailSender. */
    @Transactional
    public Optional<ClaimedEmail> prepareReminderSend(ClaimedEmail email) {
        requireReminder(email);
        Appointment appointment = appointments.findByIdForUpdate(email.appointmentId()).orElse(null);
        Communication communication = repository.findByIdForUpdate(email.id()).orElse(null);
        Instant now = clock.instant(); // Después de cualquier espera por locks, no antes.
        if (communication == null || communication.getStatus() != CommunicationStatus.PENDING) {
            return Optional.empty();
        }
        if (!isCurrent(communication, appointment, now)) {
            communication.markSuperseded(now);
            return Optional.empty();
        }
        if (!reminders.emailDeliveryEnabled() || !ownsReservation(communication, email)
                || !now.isBefore(email.leaseUntil())) {
            return Optional.empty();
        }
        communication.markClaimStarted(email.leaseUntil(), now);
        return Optional.of(snapshot(communication, email.leaseUntil()));
    }

    @Transactional
    public void recordReminderSent(ClaimedEmail email, Instant now) {
        requireReminder(email);
        // Orden de bloqueo común: appointment→communication, incluida la auditoría.
        Appointment appointment = appointments.findByIdForUpdate(email.appointmentId()).orElseThrow();
        Communication communication = repository.findByIdForUpdate(email.id()).orElseThrow();
        verifyResultIdentity(communication, email);
        CommunicationStatus previous = communication.getStatus();
        if (previous == CommunicationStatus.SENT) {
            return;
        }
        boolean lateResult = previous == CommunicationStatus.FAILED || !ownsReservation(communication, email);
        communication.recordReminderAccepted(now);
        audit.record(appointment, communication, email, AppointmentEventType.REMINDER_SENT, previous, lateResult, now);
    }

    /** Un fallo de un lease viejo nunca sobrescribe el estado de un worker posterior. */
    @Transactional
    public boolean recordReminderFailure(ClaimedEmail email, String error, Instant retryAt,
            Instant now) {
        requireReminder(email);
        Appointment appointment = appointments.findByIdForUpdate(email.appointmentId()).orElse(null);
        Communication communication = repository.findByIdForUpdate(email.id()).orElse(null);
        if (communication == null || communication.getStatus() != CommunicationStatus.PENDING
                || communication.getAttempts() != email.attempts()) {
            return false;
        }
        verifyResultIdentity(communication, email);
        Instant currentNow = clock.instant();
        if (!isCurrent(communication, appointment, currentNow)) {
            communication.markSuperseded(currentNow);
            communication.recordObsoleteReminderError(error, now);
            return false;
        }
        if (!ownsReservation(communication, email)) {
            return false;
        }
        if (email.attempts() >= communications.dispatch().maxAttempts()) {
            communication.markFailed(error, now);
            audit.record(appointment, communication, email, AppointmentEventType.REMINDER_FAILED,
                    CommunicationStatus.PENDING, false, now);
        } else {
            communication.scheduleRetry(error, retryAt, now);
        }
        return true;
    }

    private static void verifyResultIdentity(Communication communication, ClaimedEmail email) {
        if (communication.getType() != CommunicationType.REMINDER
                || !communication.getAppointmentId().equals(email.appointmentId())
                || communication.getOccurrenceVersion() != email.occurrenceVersion()
                || email.attempts() < 1 || email.attempts() > communication.getAttempts()) {
            throw new IllegalArgumentException("El resultado no corresponde a un intento del recordatorio.");
        }
    }

    private int invalidateStale(Instant now, int batchSize) {
        List<Long> ids = repository.lockInvalidReminders(now, batchSize);
        for (Communication communication : repository.findAllById(ids)) {
            communication.markSuperseded(now);
        }
        return ids.size();
    }

    private static boolean isCurrent(Communication communication, Appointment appointment, Instant now) {
        return communication.getSupersededAt() == null && appointment != null
                && appointment.getStatus() == AppointmentStatus.SCHEDULED
                && appointment.getOccurrenceVersion() == communication.getOccurrenceVersion()
                && appointment.getStartAt().isAfter(now);
    }

    private static boolean ownsReservation(Communication communication, ClaimedEmail email) {
        return communication.getAttempts() == email.attempts()
                && Objects.equals(communication.getNextRetryAt(), email.leaseUntil());
    }

    private static ClaimedEmail snapshot(Communication communication, Instant leaseUntil) {
        return new ClaimedEmail(communication.getId(), communication.getAddress(), communication.getType(),
                communication.getOccurrenceVersion(), communication.getAttempts(),
                communication.getAppointmentId(), leaseUntil);
    }

    private static void requireReminder(ClaimedEmail email) {
        if (email.type() != CommunicationType.REMINDER) {
            throw new IllegalArgumentException("El chequeo de vigencia solo corresponde a REMINDER.");
        }
    }

    /** Chequeo final para avisos existentes, sin cambiar intentos ni política de REMINDER. */
    @Transactional
    public boolean prepareSend(ClaimedEmail email) {
        if (email.type() == CommunicationType.REMINDER) {
            throw new IllegalArgumentException("REMINDER usa su preparación específica.");
        }
        Appointment appointment = appointments.findByIdForUpdate(email.appointmentId()).orElse(null);
        Communication communication = repository.findByIdForUpdate(email.id()).orElse(null);
        Instant now = clock.instant();
        if (!isOwnedPending(communication, email) || communication.getSupersededAt() != null
                || !now.isBefore(email.leaseUntil())) {
            return false;
        }
        if (!isNoticeCurrent(email, appointment, now)) {
            communication.markSuperseded(now);
            return false;
        }
        return true;
    }

    @Transactional
    public boolean markSent(ClaimedEmail email, Instant now) {
        Communication communication = repository.findByIdForUpdate(email.id()).orElse(null);
        if (!isOwnedPending(communication, email)) {
            return false;
        }
        communication.recordAccepted(now);
        return true;
    }

    @Transactional
    public void supersede(ClaimedEmail email, Instant now) {
        repository.findByIdForUpdate(email.id()).filter(row -> isOwnedPending(row, email))
                .ifPresent(row -> row.markSuperseded(now));
    }

    @Transactional
    public boolean scheduleRetry(ClaimedEmail email, String error, Instant nextRetryAt, Instant now) {
        return ownedForFailure(email).map(row -> {
            row.scheduleRetry(error, nextRetryAt, now);
            return true;
        }).orElse(false);
    }

    @Transactional
    public boolean markFailed(ClaimedEmail email, String error, Instant now) {
        return ownedForFailure(email).map(row -> {
            row.markFailed(error, now);
            return true;
        }).orElse(false);
    }

    private Optional<Communication> ownedForFailure(ClaimedEmail email) {
        Appointment appointment = appointments.findByIdForUpdate(email.appointmentId()).orElse(null);
        Communication communication = repository.findByIdForUpdate(email.id()).orElse(null);
        if (!isOwnedPending(communication, email) || communication.getSupersededAt() != null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (!isNoticeCurrent(email, appointment, now)) {
            communication.markSuperseded(now);
            return Optional.empty();
        }
        return Optional.of(communication);
    }

    private static boolean isOwnedPending(Communication row, ClaimedEmail email) {
        return row != null && email.type() != CommunicationType.REMINDER
                && row.getStatus() == CommunicationStatus.PENDING
                && row.getType() == email.type() && row.getAppointmentId().equals(email.appointmentId())
                && row.getOccurrenceVersion() == email.occurrenceVersion() && ownsReservation(row, email);
    }

    private static boolean isNoticeCurrent(ClaimedEmail email, Appointment appointment, Instant now) {
        if (email.type() == CommunicationType.FEEDBACK_REQUESTED) {
            return true; // La vigencia de feedback sigue siendo responsabilidad de FeedbackService.
        }
        if (appointment == null || appointment.getOccurrenceVersion() != email.occurrenceVersion()) {
            return false;
        }
        return email.type() == CommunicationType.CANCELLED
                ? appointment.getStatus() == AppointmentStatus.CANCELLED
                : appointment.getStatus() == AppointmentStatus.SCHEDULED && appointment.getStartAt().isAfter(now);
    }
}

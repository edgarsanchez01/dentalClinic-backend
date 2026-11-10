package com.dentalclinic.appointment.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.dentalclinic.appointment.infrastructure.config.AppointmentReminderProperties;
import com.dentalclinic.appointment.infrastructure.repository.AppointmentRepository;
import com.dentalclinic.appointment.infrastructure.repository.AppointmentRepository.ReminderCandidate;

/** Productor sin estado en memoria: cada ejecución redescubre las ocurrencias pendientes en BD. */
@Service
public class AppointmentReminderJob {

    private static final Logger log = LoggerFactory.getLogger(AppointmentReminderJob.class);

    private final AppointmentRepository appointments;
    private final AppointmentReminderService reminders;
    private final Clock clock;
    private final AppointmentReminderProperties properties;
    private final CommunicationClaimService claims;

    public AppointmentReminderJob(AppointmentRepository appointments,
            AppointmentReminderService reminders, Clock clock, AppointmentReminderProperties properties,
            CommunicationClaimService claims) {
        this.appointments = appointments;
        this.reminders = reminders;
        this.clock = clock;
        this.properties = properties;
        this.claims = claims;
    }

    /** Una ejecución acotada; el cursor solo vive durante este tick y avanza incluso ante errores. */
    public int generateOnce() {
        Instant now = clock.instant();
        // Caducidad independiente de SMTP, de la entrega REMINDER y del vencimiento de leases.
        for (int batch = 0; batch < properties.maxBatches(); batch++) {
            if (claims.supersedeInvalidReminders(now, properties.batchSize()) < properties.batchSize()) {
                break;
            }
        }
        long afterId = 0;
        int generated = 0;
        for (int batch = 0; batch < properties.maxBatches(); batch++) {
            List<ReminderCandidate> candidates = appointments.findReminderCandidates(now,
                    now.plus(AppointmentReminderService.LEAD_TIME), afterId, properties.batchSize());
            if (candidates.isEmpty()) {
                break;
            }
            for (ReminderCandidate candidate : candidates) {
                afterId = candidate.getId();
                try {
                    if (reminders.generate(candidate.getId(), candidate.getOccurrenceVersion())) {
                        generated++;
                    }
                } catch (DataIntegrityViolationException ex) {
                    // El método transaccional ya hizo rollback antes de llegar aquí.
                    if (isExpectedDuplicate(ex)) {
                        log.debug("Recordatorio ya materializado para cita {} V{}",
                                candidate.getId(), candidate.getOccurrenceVersion());
                    } else {
                        logFailure(candidate, ex);
                    }
                } catch (RuntimeException ex) {
                    logFailure(candidate, ex);
                }
            }
            if (candidates.size() < properties.batchSize()) {
                break;
            }
        }
        return generated;
    }

    private static boolean isExpectedDuplicate(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return "ci_uniq".equals(violation.getConstraintName())
                        || "communication_idempotency_key".equals(violation.getConstraintName());
            }
        }
        return false;
    }

    private static void logFailure(ReminderCandidate candidate, RuntimeException exception) {
        // No registrar dirección, contenido ni mensajes SQL que puedan incluir datos personales.
        log.warn("No se pudo generar recordatorio para cita {} V{}: {}",
                candidate.getId(), candidate.getOccurrenceVersion(), exception.getClass().getSimpleName());
    }
}

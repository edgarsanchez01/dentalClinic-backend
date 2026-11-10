package com.dentalclinic.appointment.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.dentalclinic.appointment.domain.*;
import com.dentalclinic.appointment.infrastructure.repository.AppointmentRepository;
import com.dentalclinic.appointment.infrastructure.repository.AppointmentEventRepository;
import com.dentalclinic.appointment.infrastructure.repository.AppointmentOccurrenceSnapshotRepository;
import com.dentalclinic.clinic.application.ClinicService;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.scheduling.domain.ScheduleConflictException;

/** Public application API: clinical callers never access appointment repositories. */
@Service
public class AppointmentClinicalContextService {
    private final AppointmentRepository appointments;
    private final AppointmentEventRepository events;
    private final AppointmentOccurrenceSnapshotRepository snapshots;
    private final ClinicService clinic;
    private final EntityManager entityManager;
    private final Clock clock;

    public AppointmentClinicalContextService(AppointmentRepository appointments, AppointmentEventRepository events,
            AppointmentOccurrenceSnapshotRepository snapshots, ClinicService clinic, EntityManager entityManager, Clock clock) {
        this.appointments = appointments; this.events = events; this.snapshots = snapshots;
        this.clinic = clinic; this.entityManager = entityManager; this.clock = clock;
    }

    /** Caller owns the transaction: lock and snapshot survive until the clinical entry commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public RecordedAppointmentContext capture(Long patientId, Selection selection, AuthenticatedUser actor) {
        Appointment appointment = lockForRegistration(patientId, selection, actor);
        if (appointment == null) return null;
        var key = new OccurrenceKey(appointment.getId(), appointment.getOccurrenceVersion());
        if (!snapshots.existsById(key)) {
            String timezone = ZoneId.of(clinic.get().getTimezone()).getId();
            snapshots.saveAndFlush(AppointmentOccurrenceSnapshot.capture(appointment, timezone, clock.instant()));
        }
        return new RecordedAppointmentContext(appointment.getId(), appointment.getOccurrenceVersion(), appointment.getStatus());
    }

    /** E2 needs the same lock/validation before a diff, but a no-op must not create a snapshot. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void validateForRegistration(Long patientId, Selection selection, AuthenticatedUser actor) {
        lockForRegistration(patientId, selection, actor);
    }

    private Appointment lockForRegistration(Long patientId, Selection selection, AuthenticatedUser actor) {
        requireClinical(actor);
        if (selection == null) return null;
        Appointment appointment = appointments.findByIdForUpdate(selection.appointmentId()).orElseThrow(NotFoundException::new);
        // A previous read in this persistence context must not supply a stale managed entity.
        entityManager.refresh(appointment, LockModeType.PESSIMISTIC_WRITE);
        requirePatient(appointment, patientId);
        if (appointment.getOccurrenceVersion() != selection.occurrenceVersion()) {
            throw new ScheduleConflictException("La cita fue reprogramada mientras escribías. Conserva el borrador y revisa su destino antes de guardarlo.");
        }
        if (appointment.getStatus() != AppointmentStatus.SCHEDULED && appointment.getStatus() != AppointmentStatus.COMPLETED) {
            throw new ScheduleConflictException("Esta cita está cancelada o marcada como no realizada. Puedes consultar lo anterior o guardar explícitamente un registro general.");
        }
        return appointment;
    }

    @Transactional(readOnly = true)
    public OccurrenceView read(Long patientId, Selection selection, AuthenticatedUser actor) {
        requireClinical(actor);
        Appointment appointment = appointments.findById(selection.appointmentId()).orElseThrow(NotFoundException::new);
        requirePatient(appointment, patientId);
        if (selection.occurrenceVersion() > appointment.getOccurrenceVersion() || selection.occurrenceVersion() < 1) throw new NotFoundException();
        var snapshot = snapshots.findById(new OccurrenceKey(selection.appointmentId(), selection.occurrenceVersion()));
        if (snapshot.isPresent()) {
            var s = snapshot.get();
            return new OccurrenceView(appointment.getId(), selection.occurrenceVersion(), appointment.getOccurrenceVersion(),
                    appointment.getStatus(), ContextKind.FROZEN, s.getDentistId(), s.getScheduledStartAt(),
                    s.getScheduledEndAt(), s.getTimezone(), s.getCapturedAt());
        }
        if (selection.occurrenceVersion() == appointment.getOccurrenceVersion()) {
            return new OccurrenceView(appointment.getId(), selection.occurrenceVersion(), appointment.getOccurrenceVersion(),
                    appointment.getStatus(), ContextKind.CURRENT_UNFROZEN, appointment.getDentistId(), appointment.getStartAt(),
                    appointment.getEndAt(), ZoneId.of(clinic.get().getTimezone()).getId(), null);
        }
        if (!events.existsByAppointmentIdAndOccurrenceVersion(appointment.getId(), selection.occurrenceVersion())) throw new NotFoundException();
        return new OccurrenceView(appointment.getId(), selection.occurrenceVersion(), appointment.getOccurrenceVersion(),
                appointment.getStatus(), ContextKind.HISTORICAL_UNKNOWN, null, null, null, null, null);
    }

    private static void requirePatient(Appointment appointment, Long patientId) {
        if (!appointment.getPatientId().equals(patientId)) throw new NotFoundException();
    }

    private static void requireClinical(AuthenticatedUser actor) {
        if (actor.role() != Role.OWNER && actor.role() != Role.DENTIST) throw new ForbiddenOperationException("No tienes acceso a la trazabilidad clínica.");
    }

    public record Selection(Long appointmentId, int occurrenceVersion) { }
    public enum ContextKind { FROZEN, CURRENT_UNFROZEN, HISTORICAL_UNKNOWN }
    public record OccurrenceView(Long appointmentId, int occurrenceVersion, int currentOccurrenceVersion,
            AppointmentStatus currentStatus, ContextKind kind, Long dentistId, Instant scheduledStartAt,
            Instant scheduledEndAt, String timezone, Instant capturedAt) { }
}

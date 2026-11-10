package com.dentalclinic.appointment.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.appointment.domain.AppointmentEvent;
import com.dentalclinic.appointment.domain.AppointmentEventType;
import com.dentalclinic.appointment.domain.AppointmentStatus;
import com.dentalclinic.appointment.infrastructure.repository.AppointmentEventRepository;
import com.dentalclinic.appointment.infrastructure.repository.AppointmentRepository;
import com.dentalclinic.clinic.application.DentistService;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.patient.application.PatientService;
import com.dentalclinic.scheduling.domain.ScheduleConflictException;

/**
 * Participante interno que completa una cita con todos sus efectos clinicos.
 *
 * <p>Es la extraccion del nucleo de {@link AppointmentService#complete(Long, AuthenticatedUser)}:
 * estado, {@code completedAt}, {@code updatedAt}, recordatorios pendientes, evento
 * {@code APPOINTMENT_COMPLETED}, notificacion in-app y {@link ConsultationCompleted}. Tanto la
 * ruta clinica versionada como la compatibilidad administrativa usan este participante. UX-05C
 * retira el cierre integrado, sin crear una segunda definicion de "completar".
 *
 * <p>No cobra, no crea cargos ni movimientos y no consume idempotencia. Exige una transaccion
 * ambiente mediante {@link Propagation#MANDATORY}; el llamador conserva el commit, la autorizacion
 * HTTP y el orden de locks. El acceso {@code DENTIST}/{@code OWNER} se comprueba de nuevo aqui
 * sobre datos persistidos, incluso si el llamador ya lo hizo.
 */
@Service
public class AppointmentCompletionParticipant {

    private final AppointmentRepository appointments;
    private final AppointmentEventRepository appointmentEvents;
    private final PatientService patients;
    private final DentistService dentists;
    private final AppointmentCommunicationPlanner communications;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public AppointmentCompletionParticipant(AppointmentRepository appointments,
            AppointmentEventRepository appointmentEvents, PatientService patients,
            DentistService dentists, AppointmentCommunicationPlanner communications,
            ApplicationEventPublisher events, Clock clock) {
        this.appointments = appointments;
        this.appointmentEvents = appointmentEvents;
        this.patients = patients;
        this.dentists = dentists;
        this.communications = communications;
        this.events = events;
        this.clock = clock;
    }

    /** Ruta clinica historica: bloquea, valida el estado, autoriza y completa. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Appointment complete(Long appointmentId, AuthenticatedUser actor) {
        requireWritable();
        // Compatibility entry point is administrative only. Dentists use the versioned command.
        if (actor == null || actor.role() != Role.OWNER) {
            throw new ForbiddenOperationException("Usa la finalizacion con version e Idempotency-Key.");
        }
        Appointment appointment = appointments.findByIdForUpdate(appointmentId)
                .orElseThrow(NotFoundException::new);
        requireScheduled(appointment);
        authorizeCompletion(appointment, actor);
        Instant now = clock.instant();
        finish(appointment, actor, now);
        return appointment;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Appointment completeVersioned(Long id, AuthenticatedUser actor, int expectedVersion) {
        requireWritable();
        requireCheckoutScope(actor);
        Appointment appointment = appointments.findByIdForUpdate(id).orElseThrow(NotFoundException::new);
        authorizeCompletion(appointment, actor);
        if (appointment.getOccurrenceVersion() != expectedVersion) {
            throw new ScheduleConflictException("La cita fue reprogramada; vuelve a leerla.");
        }
        requireScheduled(appointment);
        finish(appointment, actor, clock.instant().truncatedTo(ChronoUnit.MICROS));
        return appointment;
    }

    /** Administrative financial lock. Does not authorize or perform clinical completion. */
    @Transactional(propagation = Propagation.MANDATORY)
    public CheckoutAppointment lockForCollection(Long id, AuthenticatedUser actor) {
        requireWritable();
        requireCollectionRole(actor);
        return CheckoutAppointment.from(appointments.findByIdForUpdate(id).orElseThrow(NotFoundException::new));
    }

    public static void requireCollectionRole(AuthenticatedUser actor) {
        if (actor == null || actor.userId() == null || actor.userId() <= 0
                || (actor.role() != Role.OWNER && actor.role() != Role.RECEPTIONIST)) {
            throw new ForbiddenOperationException("Solo OWNER o RECEPTIONIST pueden gestionar el cobro.");
        }
    }

    /**
     * Resuelve el alcance que no depende de una cita concreta antes de bloquearla. Asi un
     * {@code DENTIST} sin perfil recibe 403 exista o no el identificador, y no puede enumerar la
     * agenda por la diferencia entre 403/404.
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public void requireCheckoutScope(AuthenticatedUser actor) {
        requireCheckoutRole(actor);
        Long dentistId = dentists.dentistIdFor(actor.userId()).orElseThrow(() ->
                new ForbiddenOperationException("Tu cuenta no tiene un perfil de dentista."));
        Objects.requireNonNull(dentistId);
    }

    /**
     * Bloquea la cita y aplica el alcance del checkout. No exige que siga {@code SCHEDULED}: un
     * replay autorizado debe poder recuperar su snapshot aunque la cita ya este completada.
     *
     * <p>Un {@code DENTIST} solo accede a su cita; una cita ajena o inexistente responde 404. Un
     * {@code OWNER} conserva la regla clinica vigente: debe ser el profesional asignado; no se
     * infiere autorizacion global para completar desde sus permisos administrativos.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CheckoutAppointment lockForCheckout(Long appointmentId, AuthenticatedUser actor) {
        requireWritable();
        Appointment appointment = appointments.findByIdForUpdate(appointmentId)
                .orElseThrow(NotFoundException::new);
        authorizeCheckout(appointment, actor);
        return CheckoutAppointment.from(appointment);
    }

    /** Retired writer, even for an assigned OWNER or an in-process caller. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void completeForCheckout(Long appointmentId, AuthenticatedUser actor,
            int expectedOccurrenceVersion, Instant completedAt) {
        throw new ForbiddenOperationException("El checkout integrado esta retirado. Usa /complete y /collection.");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void verifyCheckoutCompleted(Long id, int occurrence, Instant completedAt) {
        var appointment = appointments.findByIdForUpdate(id).orElseThrow(NotFoundException::new);
        if (appointment.getStatus() != AppointmentStatus.COMPLETED
                || appointment.getOccurrenceVersion() != occurrence
                || !Objects.equals(appointment.getCompletedAt(), completedAt)) {
            throw new IllegalStateException("El cierre no coincide con la finalizacion clinica.");
        }
    }

    private static void requireWritable() {
        var isolation = org.springframework.transaction.support.TransactionSynchronizationManager
                .getCurrentTransactionIsolationLevel();
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isCurrentTransactionReadOnly()
                || (isolation != null && isolation != java.sql.Connection.TRANSACTION_READ_COMMITTED)) {
            throw new org.springframework.transaction.IllegalTransactionStateException(
                    "El completado requiere escritura READ_COMMITTED.");
        }
    }

    private void finish(Appointment appointment, AuthenticatedUser actor, Instant now) {
        appointment.complete(now);
        communications.supersedePendingReminders(appointment.getId(),
                appointment.getOccurrenceVersion(), now);
        appointmentEvents.save(AppointmentEvent.create(appointment.getId(),
                AppointmentEventType.APPOINTMENT_COMPLETED, actor.userId(),
                appointment.getOccurrenceVersion(), null));
        var patient = patients.get(appointment.getPatientId());
        communications.notifyPatientInApp(appointment, patient, "Consulta completada",
                "Gracias por tu visita. Registramos tu consulta como completada.");
        // Listener MANDATORY de feedback: intencion/outbox/evento en esta misma transaccion.
        events.publishEvent(new ConsultationCompleted(appointment.getId(),
                appointment.getPatientId(), appointment.getOccurrenceVersion(), actor.userId(), now));
    }

    private static void requireCheckoutRole(AuthenticatedUser actor) {
        if (actor == null || actor.userId() == null || actor.role() == null
                || actor.userId() <= 0) {
            throw new ForbiddenOperationException("No hay identidad autenticada con rol.");
        }
        if (actor.role() != Role.DENTIST && actor.role() != Role.OWNER) {
            throw new ForbiddenOperationException(
                    "Solo el profesional asignado o la administracion pueden cerrar una atencion.");
        }
    }

    private void authorizeCheckout(Appointment appointment, AuthenticatedUser actor) {
        requireCheckoutRole(actor);
        Long dentistId = dentists.dentistIdFor(actor.userId()).orElseThrow(() ->
                new ForbiddenOperationException("Tu cuenta no tiene un perfil de dentista."));
        if (!dentistId.equals(appointment.getDentistId())) {
            if (actor.role() == Role.DENTIST) {
                throw new NotFoundException();
            }
            throw new ForbiddenOperationException(
                    "Esta cita no es tuya: solo el profesional asignado puede cerrarla.");
        }
    }

    /** La ruta historica conserva su matriz exacta: solo DENTIST/OWNER asignado. */
    private void authorizeCompletion(Appointment appointment, AuthenticatedUser actor) {
        if (actor.role() != Role.DENTIST && actor.role() != Role.OWNER) {
            throw new ForbiddenOperationException(
                    "Solo el profesional asignado puede completar la cita.");
        }
        Long dentistId = dentists.dentistIdFor(actor.userId()).orElseThrow(() ->
                new ForbiddenOperationException("Tu cuenta no tiene un perfil de dentista."));
        if (!dentistId.equals(appointment.getDentistId())) {
            throw new ForbiddenOperationException(
                    "Esta cita no es tuya: solo el profesional asignado puede operar sobre ella.");
        }
    }

    private static void requireScheduled(Appointment appointment) {
        if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new ScheduleConflictException(
                    "La cita ya no esta programada (estado " + appointment.getStatus() + ").");
        }
    }

    /** Vista tipada de la cita bajo lock para el coordinador de checkout. */
    public record CheckoutAppointment(Long id, Long patientId, Long dentistId,
            AppointmentStatus status, int occurrenceVersion, Instant startAt, Instant endAt,
            int durationMinutes, Long serviceId, String serviceName, String serviceDescription,
            BigDecimal serviceReferencePrice, Integer serviceDurationMinutes) {

        static CheckoutAppointment from(Appointment appointment) {
            return new CheckoutAppointment(appointment.getId(), appointment.getPatientId(),
                    appointment.getDentistId(), appointment.getStatus(),
                    appointment.getOccurrenceVersion(), appointment.getStartAt(),
                    appointment.getEndAt(), appointment.getDurationMinutes(),
                    appointment.getServiceId(), appointment.getServiceName(),
                    appointment.getServiceDescription(), appointment.getServiceReferencePrice(),
                    appointment.getServiceDurationMinutes());
        }
    }
}

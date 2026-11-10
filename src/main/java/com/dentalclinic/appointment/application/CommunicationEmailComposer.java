package com.dentalclinic.appointment.application;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.appointment.domain.CommunicationType;
import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.appointment.domain.AppointmentEventType;
import com.dentalclinic.appointment.domain.OccurrenceKey;
import com.dentalclinic.appointment.infrastructure.repository.AppointmentEventRepository;
import com.dentalclinic.appointment.infrastructure.repository.AppointmentOccurrenceSnapshotRepository;
import com.dentalclinic.appointment.infrastructure.repository.AppointmentRepository;
import com.dentalclinic.clinic.application.ClinicService;
import com.dentalclinic.clinic.application.DentistService;
import com.dentalclinic.patient.application.PatientService;

/**
 * Compone el correo a partir de la comunicación reclamada.
 *
 * <p>8C contextualiza CREATED/RESCHEDULED/CANCELLED con datos logísticos reales. REMINDER y
 * FEEDBACK_REQUESTED conservan sus plantillas. Lectura antes del chequeo final y de SMTP.
 */
@Component
public class CommunicationEmailComposer {

    private final AppointmentRepository appointments;
    private final ClinicService clinics;
    private final PatientService patients;
    private final DentistService dentists;
    private final com.dentalclinic.feedback.application.FeedbackService feedback;
    private final AppointmentEventRepository events;
    private final AppointmentOccurrenceSnapshotRepository snapshots;

    public CommunicationEmailComposer(AppointmentRepository appointments, ClinicService clinics,
            PatientService patients, DentistService dentists,
            com.dentalclinic.feedback.application.FeedbackService feedback,
            AppointmentEventRepository events, AppointmentOccurrenceSnapshotRepository snapshots) {
        this.appointments = appointments;
        this.clinics = clinics;
        this.patients = patients;
        this.dentists = dentists;
        this.feedback = feedback;
        this.events = events;
        this.snapshots = snapshots;
    }

    @Transactional(readOnly = true)
    public OutboundEmail compose(ClaimedEmail email) {
        if (email.type() == CommunicationType.FEEDBACK_REQUESTED) {
            return feedback.invitationEmail(email.appointmentId(), email.address());
        }
        if (email.type() == CommunicationType.REMINDER) {
            var appointment = appointments.findById(email.appointmentId()).orElseThrow();
            if (appointment.getOccurrenceVersion() != email.occurrenceVersion()) {
                throw new IllegalStateException("La ocurrencia del recordatorio ya cambió.");
            }
            var clinic = clinics.get();
            return new OutboundEmail(email.address(), ReminderMessageContent.subject(clinic),
                    ReminderMessageContent.emailBody(appointment, patients.get(appointment.getPatientId()),
                            clinic, dentists.get(appointment.getDentistId())));
        }
        var appointment = appointments.findById(email.appointmentId()).orElseThrow();
        var clinic = clinics.get();
        return new OutboundEmail(email.address(), AppointmentNoticeContent.subject(email.type(), clinic),
                AppointmentNoticeContent.body(email.type(), appointment, patients.get(appointment.getPatientId()),
                        clinic, dentists.get(appointment.getDentistId()), email.type() == CommunicationType.RESCHEDULED
                                ? previousSchedule(appointment) : null));
    }

    /** Solo evidencia explícita por ocurrencia; nunca deducir horas anteriores de created_at. */
    private AppointmentNoticeContent.PreviousSchedule previousSchedule(Appointment appointment) {
        var matching = events.findByAppointmentIdAndEventTypeAndOccurrenceVersion(appointment.getId(),
                AppointmentEventType.APPOINTMENT_RESCHEDULED, appointment.getOccurrenceVersion());
        if (matching.size() > 1) {
            return null; // Historial ambiguo: omitir en vez de escoger arbitrariamente.
        }
        if (matching.size() == 1 && matching.get(0).getMetadata() != null) {
            try {
                Map<String, Object> data = matching.get(0).getMetadata();
                Instant previous = Instant.parse((String) data.get("previousStartAt"));
                Instant previousEnd = Instant.parse((String) data.get("previousEndAt"));
                if (!previousEnd.isAfter(previous)
                        || !appointment.getStartAt().equals(Instant.parse((String) data.get("startAt")))
                        || !appointment.getEndAt().equals(Instant.parse((String) data.get("endAt")))) {
                    return null;
                }
                return new AppointmentNoticeContent.PreviousSchedule(previous,
                        ZoneId.of((String) data.get("timezone")));
            } catch (RuntimeException invalidHistory) {
                return null; // No registrar metadata arbitraria ni sus valores.
            }
        }
        return snapshots.findById(new OccurrenceKey(appointment.getId(), appointment.getOccurrenceVersion() - 1))
                .filter(snapshot -> snapshot.getPatientId().equals(appointment.getPatientId()))
                .filter(snapshot -> snapshot.getScheduledEndAt().isAfter(snapshot.getScheduledStartAt()))
                .map(snapshot -> {
                    try {
                        return new AppointmentNoticeContent.PreviousSchedule(snapshot.getScheduledStartAt(),
                                ZoneId.of(snapshot.getTimezone()));
                    } catch (RuntimeException invalidHistory) {
                        return null;
                    }
                }).orElse(null);
    }
}

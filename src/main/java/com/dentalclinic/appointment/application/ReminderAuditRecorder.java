package com.dentalclinic.appointment.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.appointment.domain.AppointmentEvent;
import com.dentalclinic.appointment.domain.AppointmentEventType;
import com.dentalclinic.appointment.domain.Communication;
import com.dentalclinic.appointment.domain.CommunicationStatus;
import com.dentalclinic.appointment.infrastructure.repository.AppointmentEventRepository;
import com.dentalclinic.identity.application.UserService;

/** Auditoría automática en el mismo commit de la transición, sin impersonar al creador de la cita. */
@Service
public class ReminderAuditRecorder {

    private final AppointmentEventRepository events;
    private final UserService users;

    public ReminderAuditRecorder(AppointmentEventRepository events, UserService users) {
        this.events = events;
        this.users = users;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Appointment appointment, Communication communication, ClaimedEmail email,
            AppointmentEventType type, CommunicationStatus previousStatus, boolean lateResult, Instant now) {
        // FK existente y comprobación explícita. Activo/rol no condicionan una referencia de auditoría.
        Long technicalReference = users.getById(appointment.getCreatedByUserId()).getId();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "SCHEDULER");
        metadata.put("automatic", true);
        metadata.put("humanAction", false);
        metadata.put("actorReference", "APPOINTMENT_CREATOR");
        metadata.put("communicationId", communication.getId());
        metadata.put("occurrenceVersion", communication.getOccurrenceVersion());
        metadata.put("channel", "EMAIL");
        metadata.put("result", type == AppointmentEventType.REMINDER_SENT ? "SMTP_ACCEPTED" : "ATTEMPTS_EXHAUSTED");
        metadata.put("previousStatus", previousStatus.name());
        metadata.put("status", communication.getStatus().name());
        metadata.put("attempt", email.attempts());
        metadata.put("attempts", communication.getAttempts());
        metadata.put("resultAt", now.toString());
        metadata.put("lateResult", lateResult);
        metadata.put("superseded", communication.getSupersededAt() != null);
        metadata.put("templateVersion", ReminderMessageContent.TEMPLATE_VERSION);
        if (communication.getSupersededAt() != null) {
            metadata.put("supersededAt", communication.getSupersededAt().toString());
        }
        if (type == AppointmentEventType.REMINDER_FAILED && communication.getLastError() != null) {
            metadata.put("error", communication.getLastError());
        }
        events.save(AppointmentEvent.create(appointment.getId(), type, technicalReference,
                communication.getOccurrenceVersion(), metadata));
    }
}

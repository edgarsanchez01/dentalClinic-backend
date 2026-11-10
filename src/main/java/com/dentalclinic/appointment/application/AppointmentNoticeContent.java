package com.dentalclinic.appointment.application;

import java.time.Instant;
import java.time.ZoneId;

import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.appointment.domain.CommunicationType;
import com.dentalclinic.clinic.domain.Clinic;
import com.dentalclinic.clinic.domain.Dentist;
import com.dentalclinic.patient.domain.Patient;

/** Texto logístico v1 para los eventos existentes CREATED/RESCHEDULED/CANCELLED, sin clínica sensible. */
final class AppointmentNoticeContent {
    static final String TEMPLATE_VERSION = "appointment-notice-text-v1";

    private AppointmentNoticeContent() {
    }

    static String subject(CommunicationType type, Clinic clinic) {
        String event = switch (type) {
            case CREATED -> "Cita registrada";
            case RESCHEDULED -> "Cita reprogramada";
            case CANCELLED -> "Cita cancelada";
            default -> throw new IllegalArgumentException("Tipo de aviso de cita no compatible.");
        };
        String name = ReminderMessageContent.text(clinic.getName());
        return event + (name.isEmpty() ? "" : " — " + name);
    }

    static String body(CommunicationType type, Appointment appointment, Patient patient, Clinic clinic,
            Dentist dentist, PreviousSchedule previous) {
        StringBuilder body = new StringBuilder("Hola:\n\n");
        body.append(switch (type) {
            case CREATED -> "Se ha registrado una cita.";
            case RESCHEDULED -> "Se ha reprogramado una cita. El horario nuevo sustituye al anterior.";
            case CANCELLED -> "Esta cita fue cancelada y ya no está vigente.";
            default -> throw new IllegalArgumentException("Tipo de aviso de cita no compatible.");
        }).append("\n\n");
        ReminderMessageContent.line(body, "Paciente",
                ReminderMessageContent.name(patient.getFirstName(), patient.getLastName()));
        if (type == CommunicationType.RESCHEDULED && previous != null) {
            ReminderMessageContent.line(body, "Horario anterior",
                    ReminderMessageContent.dateTime(previous.startAt(), previous.zone()));
        }
        String label = switch (type) {
            case RESCHEDULED -> "Horario nuevo";
            case CANCELLED -> "Horario de la cita cancelada";
            default -> "Fecha y hora";
        };
        ReminderMessageContent.line(body, label,
                ReminderMessageContent.dateTime(appointment.getStartAt(), ZoneId.of(clinic.getTimezone())));
        if (type != CommunicationType.CANCELLED) {
            body.append("Duración prevista: ").append(appointment.getDurationMinutes()).append(" minutos\n");
        }
        ReminderMessageContent.details(body, clinic, dentist);
        return body.append("\nMensaje automático de DentalClinic.\n").toString();
    }

    record PreviousSchedule(Instant startAt, ZoneId zone) {
    }
}

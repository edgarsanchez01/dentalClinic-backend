package com.dentalclinic.appointment.application;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.stream.Collectors;

import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.clinic.domain.Clinic;
import com.dentalclinic.clinic.domain.Dentist;
import com.dentalclinic.patient.domain.Patient;

/** Plantilla de texto v1 compartida por REMINDER EMAIL e in-app; sin reloj ni zona implícita. */
final class ReminderMessageContent {

    static final String TEMPLATE_VERSION = "appointment-reminder-text-v1";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern(
            "EEEE d 'de' MMMM 'de' uuuu 'a las' HH:mm", Locale.forLanguageTag("es"));

    private ReminderMessageContent() {
    }

    static String subject(Clinic clinic) {
        String name = text(clinic.getName());
        return "Recordatorio de tu cita dental" + (name.isEmpty() ? "" : " — " + name);
    }

    static String emailBody(Appointment appointment, Patient patient, Clinic clinic, Dentist dentist) {
        String name = name(patient.getFirstName(), patient.getLastName());
        StringBuilder body = new StringBuilder(name.isEmpty() ? "Hola:\n\n" : "Hola, " + name + ":\n\n");
        body.append("Te recordamos que tienes una cita programada para el ")
                .append(dateTime(appointment.getStartAt(), ZoneId.of(clinic.getTimezone()))).append(".\n\n");
        details(body, clinic, dentist);
        return body.append("\n¡Te esperamos!\n\nMensaje automático de DentalClinic.\n").toString();
    }

    static String notificationBody(Appointment appointment, Clinic clinic, Dentist dentist) {
        StringBuilder body = new StringBuilder("Recuerda tu cita para el ")
                .append(dateTime(appointment.getStartAt(), ZoneId.of(clinic.getTimezone()))).append(".\n");
        details(body, clinic, dentist);
        return body.toString().strip();
    }

    static void details(StringBuilder body, Clinic clinic, Dentist dentist) {
        if (dentist != null) {
            line(body, "Profesional", name(dentist.getFirstName(), dentist.getLastName()));
        }
        line(body, "Clínica", clinic.getName());
        line(body, "Dirección", clinic.getAddress());
        line(body, "Teléfono", clinic.getPhone());
        line(body, "Correo de contacto", clinic.getEmail());
    }

    static String dateTime(Instant instant, ZoneId zone) {
        var local = instant.atZone(zone);
        String offset = local.getOffset().getId();
        return DATE_TIME.format(local) + " (" + zone.getId() + ", UTC"
                + (offset.equals("Z") ? "+00:00" : offset) + ")";
    }

    static void line(StringBuilder body, String label, String value) {
        String content = text(value);
        if (!content.isEmpty()) {
            body.append(label).append(": ").append(content).append('\n');
        }
    }

    static String name(String first, String last) {
        return Stream.of(first, last).map(ReminderMessageContent::text).filter(part -> !part.isEmpty())
                .collect(Collectors.joining(" "));
    }

    static String text(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }
}

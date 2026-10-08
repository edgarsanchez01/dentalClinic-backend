package com.dentalclinic.appointment.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Solicitud de reprogramación (RF-AP-18). {@code expectedOccurrenceVersion} es la versión que la
 * pantalla está viendo (A-09): si la cita cambió en otra petición, la reprogramación se rechaza con
 * 409 y el cliente recarga la versión actual (se evita pisar cambios ajenos).
 */
public record RescheduleAppointmentRequest(
        @NotNull(message = "El inicio de la cita es obligatorio.") Instant startAt,
        @NotNull(message = "El fin de la cita es obligatorio.") Instant endAt,
        @Min(value = 1, message = "La versión esperada de la cita no es válida.") int expectedOccurrenceVersion) {
}
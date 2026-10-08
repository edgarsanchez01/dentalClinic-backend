package com.dentalclinic.appointment.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;

/**
 * Solicitud de creación de cita (RF-AP-*). El rango horario llega en instantes ISO-8601; la
 * validación de horario hábil y ocupación la hace el servidor contra la disponibilidad (A-20).
 */
public record CreateAppointmentRequest(
        @NotNull(message = "El paciente es obligatorio.") Long patientId,
        @NotNull(message = "El profesional es obligatorio.") Long dentistId,
        @NotNull(message = "El inicio de la cita es obligatorio.") Instant startAt,
        @NotNull(message = "El fin de la cita es obligatorio.") Instant endAt,
        String reason,
        String notes) {
}
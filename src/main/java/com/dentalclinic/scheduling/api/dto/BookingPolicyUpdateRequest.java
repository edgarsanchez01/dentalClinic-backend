package com.dentalclinic.scheduling.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Actualización de la política de reservas (solo OWNER). */
public record BookingPolicyUpdateRequest(
        @NotNull(message = "El horizonte de días es obligatorio.")
        @Min(value = 0, message = "El horizonte no puede ser negativo.") Integer maxAdvanceDays,
        @NotNull(message = "La antelación mínima de reserva es obligatoria.")
        @Min(value = 0, message = "La antelación mínima no puede ser negativa.") Integer minBookingLeadTimeHours,
        @NotNull(message = "La antelación de cancelación es obligatoria.")
        @Min(value = 0, message = "La antelación de cancelación no puede ser negativa.") Integer cancellationLeadTimeHours) {
}
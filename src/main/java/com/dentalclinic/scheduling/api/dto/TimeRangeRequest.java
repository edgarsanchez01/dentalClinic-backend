package com.dentalclinic.scheduling.api.dto;

import java.time.LocalTime;

import jakarta.validation.constraints.NotNull;

/** Franja horaria de un break en una petición de horario. */
public record TimeRangeRequest(
        @NotNull(message = "La hora de inicio es obligatoria.") LocalTime startTime,
        @NotNull(message = "La hora de fin es obligatoria.") LocalTime endTime) {
}
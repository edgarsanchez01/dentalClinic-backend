package com.dentalclinic.scheduling.api.dto;

import java.time.LocalDate;
import java.time.LocalTime;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Franquicia. {@code allDay=true} ⇒ días completos de {@code startDate} a {@code endDate}. */
public record TimeOffRequest(
        @NotNull(message = "La fecha de inicio es obligatoria.") LocalDate startDate,
        @NotNull(message = "La fecha de fin es obligatoria.") LocalDate endDate,
        LocalTime startTime,
        LocalTime endTime,
        @Size(max = 500, message = "El motivo no puede superar 500 caracteres.") String reason) {
}
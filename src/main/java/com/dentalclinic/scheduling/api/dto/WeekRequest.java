package com.dentalclinic.scheduling.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Petición de reemplazo semanal completo (7 días, abiertos o cerrados). */
public record WeekRequest(
        @NotNull(message = "Los días del horario son obligatorios.")
        @Valid List<WeekDayRequest> days) {
}
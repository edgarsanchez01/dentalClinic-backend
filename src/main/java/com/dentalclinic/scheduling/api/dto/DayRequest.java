package com.dentalclinic.scheduling.api.dto;

import java.time.LocalTime;
import java.util.List;

import jakarta.validation.Valid;

/** Petición de reemplazo de un solo día del horario semanal. */
public record DayRequest(
        boolean open,
        LocalTime startTime,
        LocalTime endTime,
        @Valid List<TimeRangeRequest> breaks) {
}
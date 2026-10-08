package com.dentalclinic.scheduling.api.dto;

import java.time.LocalTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Día dentro de una petición de horario semanal completo. */
public record WeekDayRequest(
        @Min(value = 1, message = "El día debe estar entre 1 (lunes) y 7 (domingo).")
        @Max(value = 7, message = "El día debe estar entre 1 (lunes) y 7 (domingo).")
        int dayOfWeek,
        boolean open,
        LocalTime startTime,
        LocalTime endTime,
        @Valid List<TimeRangeRequest> breaks) {
}
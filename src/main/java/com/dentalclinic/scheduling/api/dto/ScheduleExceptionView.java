package com.dentalclinic.scheduling.api.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/** Excepción por fecha (de clínica o de dentista). */
public record ScheduleExceptionView(
        LocalDate date,
        boolean closed,
        LocalTime startTime,
        LocalTime endTime,
        String reason) {
}
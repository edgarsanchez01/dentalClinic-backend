package com.dentalclinic.scheduling.api.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/** Excepción de clínica en el horario público, deliberadamente sin {@code reason}. */
public record PublicExceptionView(
        LocalDate date,
        boolean closed,
        LocalTime startTime,
        LocalTime endTime) {
}
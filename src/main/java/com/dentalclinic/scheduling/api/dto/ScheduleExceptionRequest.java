package com.dentalclinic.scheduling.api.dto;

import java.time.LocalTime;

import jakarta.validation.constraints.Size;

/**
 * Alta/actualización de una excepción por fecha. Si {@code closed=true} las horas se ignoran y se
 * normalizan a {@code null}; si {@code closed=false} son obligatorias y {@code start<end}.
 */
public record ScheduleExceptionRequest(
        boolean closed,
        LocalTime startTime,
        LocalTime endTime,
        @Size(max = 500, message = "El motivo no puede superar 500 caracteres.") String reason) {
}
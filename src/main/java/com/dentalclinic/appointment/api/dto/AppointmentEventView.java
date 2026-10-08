package com.dentalclinic.appointment.api.dto;

import java.time.Instant;
import java.util.Map;

import com.dentalclinic.appointment.domain.AppointmentEventType;

/**
 * Evento de auditoría de una cita (RF-AP-05/15). No es estado: es la secuencia append-only de lo
 * que ocurrió, con el actor y la ocurrencia en la que sucedió.
 */
public record AppointmentEventView(
        Long id,
        AppointmentEventType eventType,
        Long actorUserId,
        int occurrenceVersion,
        Map<String, Object> metadata,
        Instant createdAt) {
}
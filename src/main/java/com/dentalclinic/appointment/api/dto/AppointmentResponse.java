package com.dentalclinic.appointment.api.dto;

import java.time.Instant;

import com.dentalclinic.appointment.domain.AppointmentStatus;

/**
 * Representación de una cita para la agenda. Incluye los nombres del paciente y del dentista (que
 * viven en sus módulos, no aquí) para que la agenda no necesite llamadas adicionales por fila.
 */
public record AppointmentResponse(
        Long id,
        Long patientId,
        String patientFirstName,
        String patientLastName,
        Long patientUserId,
        Long dentistId,
        String dentistFirstName,
        String dentistLastName,
        Instant startAt,
        Instant endAt,
        int durationMinutes,
        AppointmentStatus status,
        int occurrenceVersion,
        Long createdByUserId,
        String reason,
        String notes,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt) {
}
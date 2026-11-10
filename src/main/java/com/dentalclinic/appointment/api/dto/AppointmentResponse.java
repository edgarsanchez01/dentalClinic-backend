package com.dentalclinic.appointment.api.dto;

import java.time.Instant;

import com.dentalclinic.appointment.domain.AppointmentStatus;
import com.dentalclinic.identity.domain.Role;

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
        Instant updatedAt,
        AppointmentServiceSnapshot service) {

    /** PRD §11.5: las notas internas nunca forman parte de la respuesta PATIENT. */
    public AppointmentResponse forRole(Role role) {
        return switch (role) {
            case OWNER, DENTIST, RECEPTIONIST -> this;
            case PATIENT -> new AppointmentResponse(id, patientId, patientFirstName,
                    patientLastName, patientUserId, dentistId, dentistFirstName, dentistLastName,
                    startAt, endAt, durationMinutes, status, occurrenceVersion, createdByUserId,
                    reason, null, completedAt, createdAt, updatedAt, service);
        };
    }
}

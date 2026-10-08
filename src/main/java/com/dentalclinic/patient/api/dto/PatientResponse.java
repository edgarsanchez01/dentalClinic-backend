package com.dentalclinic.patient.api.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Paciente en las respuestas administrativas ({@code /api/patients}). Nunca expone datos clinicos:
 * el expediente vive en {@code /api/patients/{id}/clinical}.
 */
public record PatientResponse(
        Long id,
        Long userId,
        String firstName,
        String lastName,
        String email,
        String phone,
        LocalDate birthDate,
        String administrativeNotes,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {
}
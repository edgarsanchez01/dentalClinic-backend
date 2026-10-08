package com.dentalclinic.scheduling.api.dto;

import java.util.List;

/** Horario semanal público de un dentista (solo lectura, sin breaks ni franquicias). */
public record PublicDentistView(
        Long dentistId,
        String firstName,
        String lastName,
        String specialty,
        String photoUrl,
        List<PublicDayView> days) {
}
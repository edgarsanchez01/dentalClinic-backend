package com.dentalclinic.scheduling.api.dto;

import java.util.List;

/** Disponibilidad calculada (A-06): nada de esto se persiste. */
public record AvailabilityResponse(
        Long dentistId,
        int durationMinutes,
        String timezone,
        List<AvailabilitySlot> slots) {
}
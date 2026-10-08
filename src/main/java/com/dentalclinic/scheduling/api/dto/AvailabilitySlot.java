package com.dentalclinic.scheduling.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** Slot de disponibilidad calculado: hora local y su instante resuelto de forma inequívoca. */
public record AvailabilitySlot(
        LocalDate startDate,
        LocalTime startTime,
        LocalTime endTime,
        Instant start,
        Instant end) {
}
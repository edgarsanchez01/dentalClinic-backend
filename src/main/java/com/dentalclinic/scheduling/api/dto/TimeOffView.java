package com.dentalclinic.scheduling.api.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/** Franquicia. {@code allDay=true} ⇒ días completos de {@code startDate} a {@code endDate}. */
public record TimeOffView(
        Long id,
        LocalDate startDate,
        LocalDate endDate,
        boolean allDay,
        LocalTime startTime,
        LocalTime endTime,
        String reason) {
}
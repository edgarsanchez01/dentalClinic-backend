package com.dentalclinic.scheduling.api.dto;

import java.time.LocalTime;

/** Franja horaria local (wall-clock) dentro de un día. */
public record TimeRangeView(LocalTime startTime, LocalTime endTime) {
}
package com.dentalclinic.scheduling.api.dto;

import java.time.LocalTime;

/** Día del horario público. Nunca expone breaks, franquicias ni motivos. */
public record PublicDayView(int dayOfWeek, boolean open, LocalTime startTime, LocalTime endTime) {
}
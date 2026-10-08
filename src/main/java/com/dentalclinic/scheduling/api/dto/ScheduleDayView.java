package com.dentalclinic.scheduling.api.dto;

import java.time.LocalTime;
import java.util.List;

/** Un día del horario semanal. {@code open=false} implica horas nulas y sin breaks. */
public record ScheduleDayView(
        int dayOfWeek,
        boolean open,
        LocalTime startTime,
        LocalTime endTime,
        List<TimeRangeView> breaks) {
}
package com.dentalclinic.scheduling.api.dto;

import java.util.List;

/** Horario público de la clínica y de sus dentistas (RF-SC-08). Sin datos privados. */
public record PublicScheduleView(
        String timezone,
        List<PublicDayView> days,
        List<PublicExceptionView> exceptions,
        List<PublicDentistView> dentists) {
}
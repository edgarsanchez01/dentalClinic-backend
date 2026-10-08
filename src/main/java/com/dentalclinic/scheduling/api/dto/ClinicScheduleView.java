package com.dentalclinic.scheduling.api.dto;

import java.util.List;

/** Horario semanal de la clínica con su zona IANA. */
public record ClinicScheduleView(String timezone, List<ScheduleDayView> days) {
}
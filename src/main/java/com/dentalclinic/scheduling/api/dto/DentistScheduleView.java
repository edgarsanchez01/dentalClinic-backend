package com.dentalclinic.scheduling.api.dto;

import java.util.List;

/** Horario semanal de un dentista con la zona IANA de la clínica. */
public record DentistScheduleView(Long dentistId, String timezone, List<ScheduleDayView> days) {
}
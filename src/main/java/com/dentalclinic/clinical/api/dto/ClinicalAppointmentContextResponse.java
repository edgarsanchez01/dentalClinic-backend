package com.dentalclinic.clinical.api.dto;

import com.dentalclinic.appointment.domain.AppointmentStatus;
import com.dentalclinic.appointment.domain.RecordedAppointmentContext;

public record ClinicalAppointmentContextResponse(Long appointmentId, int occurrenceVersion, AppointmentStatus statusAtRegistration) {
    public static ClinicalAppointmentContextResponse from(RecordedAppointmentContext context) {
        return context == null ? null : new ClinicalAppointmentContextResponse(context.getAppointmentId(),
                context.getOccurrenceVersion(), context.getStatusAtRegistration());
    }
}

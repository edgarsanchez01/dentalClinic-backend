package com.dentalclinic.scheduling.api.dto;

/** Política de reservas de la clínica (RF-AV-07/08/11) con su zona IANA. */
public record BookingPolicyView(
        int maxAdvanceDays,
        int minBookingLeadTimeHours,
        int cancellationLeadTimeHours,
        String timezone) {
}
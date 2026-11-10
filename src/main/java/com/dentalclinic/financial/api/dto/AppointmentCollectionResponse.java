package com.dentalclinic.financial.api.dto;

import java.time.Instant;
import com.dentalclinic.appointment.domain.AppointmentStatus;
import com.dentalclinic.financial.application.ContextualCheckoutFinancialParticipant.Execution;

/** Original committed receipt, not the current balance on replay. No fictitious checkoutId. */
public record AppointmentCollectionResponse(Long appointmentId, AppointmentStatus appointmentStatus,
        int occurrenceVersion, Instant collectedAt, Execution financialAtCollection) { }

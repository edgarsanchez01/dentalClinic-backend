package com.dentalclinic.appointment.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** No clinical content: records must already have been saved through their own endpoints. */
public record CompleteAppointmentRequest(@NotNull @Min(1) Integer expectedOccurrenceVersion) { }

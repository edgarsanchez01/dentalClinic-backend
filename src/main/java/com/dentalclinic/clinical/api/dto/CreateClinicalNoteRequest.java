package com.dentalclinic.clinical.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;

/** Alta de una nota clinica (RF-CX-04). */
public record CreateClinicalNoteRequest(
        @NotBlank(message = "La nota no puede estar vacia.")
        String content,
        @Valid AppointmentContextRequest appointmentContext) {
}

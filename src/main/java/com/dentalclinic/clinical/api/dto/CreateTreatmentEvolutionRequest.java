package com.dentalclinic.clinical.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

/**
 * Alta de una entrada de evolucion de un tratamiento (RF-TR-07). Texto clinico libre; obligatorio
 * y de hasta 4000 caracteres.
 */
public record CreateTreatmentEvolutionRequest(
        @NotBlank(message = "La evolucion del tratamiento es obligatoria.")
        @Size(max = 4000, message = "La evolucion admite hasta 4000 caracteres.")
        String content,
        @Valid AppointmentContextRequest appointmentContext) {
}

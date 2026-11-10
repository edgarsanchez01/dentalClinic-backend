package com.dentalclinic.clinical.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Alta de una correccion de una entrada de evolucion (Fase 1B.2). El contenido es el texto
 * corregido completo (no un parche parcial) y el motivo es obligatorio.
 */
public record CreateTreatmentEvolutionCorrectionRequest(
        @NotBlank(message = "La evolucion corregida es obligatoria.")
        @Size(max = 4000, message = "La evolucion admite hasta 4000 caracteres.")
        String content,
        @NotBlank(message = "El motivo de la correccion es obligatorio.")
        @Size(max = 1000, message = "El motivo admite hasta 1000 caracteres.")
        String correctionReason) {
}

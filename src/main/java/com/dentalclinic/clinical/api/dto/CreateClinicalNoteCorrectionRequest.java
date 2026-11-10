package com.dentalclinic.clinical.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Alta de una correccion de una nota clinica (Fase 1B.2). El contenido es el texto corregido
 * completo (no un parche parcial) y el motivo es obligatorio.
 */
public record CreateClinicalNoteCorrectionRequest(
        @NotBlank(message = "La nota corregida no puede estar vacia.")
        String content,
        @NotBlank(message = "El motivo de la correccion es obligatorio.")
        @Size(max = 1000, message = "El motivo admite hasta 1000 caracteres.")
        String correctionReason) {
}

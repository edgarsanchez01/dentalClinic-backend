package com.dentalclinic.clinical.api.dto;

import java.math.BigDecimal;

import com.dentalclinic.clinical.domain.TreatmentStatus;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;

/**
 * Alta de un tratamiento clinico (RF-TR-04). Si no se indica {@code status}, nace
 * {@code PLANNED}. {@code appointmentId} es opcional (RF-TR-05) y debe pertenecer al paciente.
 */
public record CreateTreatmentRequest(
        @NotBlank(message = "La descripcion del tratamiento es obligatoria.")
        String description,
        String diagnosis,
        TreatmentStatus status,
        Long appointmentId,
        String notes,
        @DecimalMin(value = "0.00", message = "El precio no puede ser negativo.")
        @Digits(integer = 15, fraction = 4, message = "El precio admite hasta 15 enteros y 4 decimales.")
        BigDecimal price) {
}

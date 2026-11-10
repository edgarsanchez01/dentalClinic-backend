package com.dentalclinic.clinical.api.dto;

import java.math.BigDecimal;

import com.dentalclinic.clinical.domain.TreatmentStatus;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Edicion del contenido clinico de un tratamiento, incluida la gestion de estado (RF-TR-06).
 * La referencia a la cita no se modifica una vez creado el tratamiento.
 */
public record UpdateTreatmentRequest(
        @NotBlank(message = "La descripcion del tratamiento es obligatoria.")
        String description,
        String diagnosis,
        @NotNull(message = "El estado del tratamiento es obligatorio.")
        TreatmentStatus status,
        String notes,
        @DecimalMin(value = "0.00", message = "El precio no puede ser negativo.")
        @Digits(integer = 15, fraction = 4, message = "El precio admite hasta 15 enteros y 4 decimales.")
        BigDecimal price) {
}

package com.dentalclinic.odontogram.api.dto;

import com.dentalclinic.odontogram.domain.ToothCondition;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import com.dentalclinic.clinical.api.dto.AppointmentContextRequest;

/**
 * Edición de la condición y observaciones de una superficie. La superficie se identifica por la
 * ruta, no por el cuerpo.
 */
public record UpdateToothSurfaceRequest(
        @NotNull(message = "La condición de la superficie es obligatoria.")
        ToothCondition condition,
        String notes,
        @Valid AppointmentContextRequest appointmentContext) {
}

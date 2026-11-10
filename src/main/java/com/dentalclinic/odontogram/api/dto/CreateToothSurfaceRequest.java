package com.dentalclinic.odontogram.api.dto;

import com.dentalclinic.odontogram.application.OdontogramService.SurfaceData;
import com.dentalclinic.odontogram.domain.ToothCondition;
import com.dentalclinic.odontogram.domain.ToothSurface;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import com.dentalclinic.clinical.api.dto.AppointmentContextRequest;

/**
 * Alta del registro vigente de una superficie de una pieza (RF-OD-02/03). La pieza se identifica
 * por la ruta FDI; el cuerpo declara la superficie, que debe pertenecer al grupo del diente.
 */
public record CreateToothSurfaceRequest(
        @NotNull(message = "La superficie es obligatoria.")
        ToothSurface surface,
        @NotNull(message = "La condición de la superficie es obligatoria.")
        ToothCondition condition,
        String notes,
        @Valid AppointmentContextRequest appointmentContext) {

    public SurfaceData toData() {
        return new SurfaceData(surface, condition, notes);
    }
}

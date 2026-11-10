package com.dentalclinic.odontogram.api.dto;

import java.util.List;

import com.dentalclinic.odontogram.application.OdontogramService.ToothData;
import com.dentalclinic.odontogram.domain.ToothCondition;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import com.dentalclinic.clinical.api.dto.AppointmentContextRequest;

/**
 * Edición de la condición y observaciones de una pieza. La pieza se identifica por la ruta FDI,
 * no por el cuerpo. {@code treatmentIds} reemplaza los tratamientos vinculados (RF-OD-05).
 */
public record UpdateToothRecordRequest(
        @NotNull(message = "La condición de la pieza es obligatoria.")
        ToothCondition condition,
        String notes,
        List<Long> treatmentIds,
        @Valid AppointmentContextRequest appointmentContext) {

    public ToothData toData(int toothFdi) {
        return new ToothData(toothFdi, condition, notes, treatmentIds);
    }
}

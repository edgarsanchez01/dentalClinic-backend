package com.dentalclinic.odontogram.api.dto;

import java.util.List;

import com.dentalclinic.odontogram.application.OdontogramService.ToothData;
import com.dentalclinic.odontogram.domain.ToothCondition;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import com.dentalclinic.clinical.api.dto.AppointmentContextRequest;

/**
 * Alta del registro vigente de una pieza dental (RF-OD-01/03). El código FDI es obligatorio y debe
 * pertenecer a la notación estándar; {@code treatmentIds} es opcional (RF-OD-05).
 */
public record CreateToothRecordRequest(
        @NotNull(message = "La pieza dental (FDI) es obligatoria.")
        Integer toothFdi,
        @NotNull(message = "La condición de la pieza es obligatoria.")
        ToothCondition condition,
        String notes,
        List<Long> treatmentIds,
        @Valid AppointmentContextRequest appointmentContext) {

    public ToothData toData() {
        return new ToothData(toothFdi, condition, notes, treatmentIds);
    }
}

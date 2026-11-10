package com.dentalclinic.odontogram.api.dto;

import java.time.Instant;
import java.util.List;

import com.dentalclinic.odontogram.application.OdontogramService.ToothView;
import com.dentalclinic.odontogram.domain.Dentition;
import com.dentalclinic.odontogram.domain.ToothCondition;
import com.dentalclinic.odontogram.domain.ToothNotation;

/**
 * Pieza del odontograma en respuestas del personal clínico (RF-OD-01/02/03/04/05): estado vigente,
 * historial de cambios, superficies y tratamientos vinculados. La dentición se deriva del código FDI.
 */
public record ToothRecordResponse(
        Long id,
        Long patientId,
        int toothFdi,
        Dentition dentition,
        ToothCondition condition,
        String notes,
        List<Long> treatmentIds,
        Instant createdAt,
        Instant updatedAt,
        List<ToothHistoryResponse> history,
        List<ToothSurfaceResponse> surfaces) {

    public static ToothRecordResponse from(ToothView view) {
        return new ToothRecordResponse(view.record().getId(), view.record().getPatientId(),
                view.record().getToothFdi(), ToothNotation.dentitionOf(view.record().getToothFdi()),
                view.record().getCondition(), view.record().getNotes(), view.treatmentIds(),
                view.record().getCreatedAt(), view.record().getUpdatedAt(),
                view.history().stream().map(ToothHistoryResponse::from).toList(),
                view.surfaces().stream().map(ToothSurfaceResponse::from).toList());
    }
}

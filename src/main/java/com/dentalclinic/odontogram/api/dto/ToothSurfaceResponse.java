package com.dentalclinic.odontogram.api.dto;

import java.time.Instant;
import java.util.List;

import com.dentalclinic.odontogram.application.OdontogramService.ToothSurfaceView;
import com.dentalclinic.odontogram.domain.ToothCondition;
import com.dentalclinic.odontogram.domain.ToothSurface;

/**
 * Superficie del odontograma en respuestas del personal clínico (RF-OD-02/03/04): estado vigente
 * con sus observaciones e historial de cambios.
 */
public record ToothSurfaceResponse(
        Long id,
        Long toothRecordId,
        ToothSurface surface,
        ToothCondition condition,
        String notes,
        Instant createdAt,
        Instant updatedAt,
        List<ToothSurfaceHistoryResponse> history) {

    public static ToothSurfaceResponse from(ToothSurfaceView view) {
        return new ToothSurfaceResponse(view.id(), view.toothRecordId(), view.surface(),
                view.condition(), view.notes(), view.createdAt(), view.updatedAt(),
                view.history().stream().map(ToothSurfaceHistoryResponse::from).toList());
    }
}
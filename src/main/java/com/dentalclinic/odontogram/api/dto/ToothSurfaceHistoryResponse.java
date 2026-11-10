package com.dentalclinic.odontogram.api.dto;

import java.time.Instant;

import com.dentalclinic.odontogram.application.OdontogramService.ToothSurfaceHistoryView;
import com.dentalclinic.odontogram.domain.ToothCondition;

/** Cambio de condición de una superficie con autor y momento (RF-OD-04). */
public record ToothSurfaceHistoryResponse(
        Long id,
        ToothCondition previousCondition,
        ToothCondition newCondition,
        Long actorUserId,
        String actorName,
        Instant changedAt) {

    public static ToothSurfaceHistoryResponse from(ToothSurfaceHistoryView view) {
        return new ToothSurfaceHistoryResponse(view.id(), view.previousCondition(), view.newCondition(),
                view.actorUserId(), view.actorName(), view.changedAt());
    }
}
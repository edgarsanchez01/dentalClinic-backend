package com.dentalclinic.odontogram.api.dto;

import java.time.Instant;

import com.dentalclinic.odontogram.application.OdontogramService.ToothHistoryView;
import com.dentalclinic.odontogram.domain.ToothCondition;

/** Cambio de condición de una pieza con autor y momento (RF-OD-04). */
public record ToothHistoryResponse(
        Long id,
        ToothCondition previousCondition,
        ToothCondition newCondition,
        Long actorUserId,
        String actorName,
        Instant changedAt) {

    public static ToothHistoryResponse from(ToothHistoryView view) {
        return new ToothHistoryResponse(view.id(), view.previousCondition(), view.newCondition(),
                view.actorUserId(), view.actorName(), view.changedAt());
    }
}

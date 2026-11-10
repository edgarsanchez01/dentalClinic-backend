package com.dentalclinic.odontogram.api.dto;

import com.dentalclinic.odontogram.application.OdontogramService.ToothSurfaceView;
import com.dentalclinic.odontogram.domain.ToothCondition;
import com.dentalclinic.odontogram.domain.ToothSurface;

/**
 * Resumen de una superficie del odontograma que ve el propio PATIENT (matriz §7.4 {@code r
 * (resumen)}): solo superficie y condición, sin observaciones ni historial ni autor. Es la
 * contrapartida de {@link OdontogramSummaryResponse} para el endpoint de superficies.
 */
public record ToothSurfaceSummaryResponse(
        ToothSurface surface,
        ToothCondition condition) {

    public static ToothSurfaceSummaryResponse from(ToothSurfaceView view) {
        return new ToothSurfaceSummaryResponse(view.surface(), view.condition());
    }
}
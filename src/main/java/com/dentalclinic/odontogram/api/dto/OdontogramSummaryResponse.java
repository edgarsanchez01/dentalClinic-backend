package com.dentalclinic.odontogram.api.dto;

import com.dentalclinic.odontogram.application.OdontogramService.ToothView;
import com.dentalclinic.odontogram.domain.Dentition;
import com.dentalclinic.odontogram.domain.ToothCondition;
import com.dentalclinic.odontogram.domain.ToothNotation;

/**
 * Resumen del odontograma que ve el propio PATIENT (matriz §7.4: {@code r (resumen)}). Es un DTO
 * <strong>reducido y separado</strong> del de personal clínico: solo pieza, dentición y condición;
 * sin observaciones, historial ni autor.
 */
public record OdontogramSummaryResponse(
        int toothFdi,
        Dentition dentition,
        ToothCondition condition) {

    public static OdontogramSummaryResponse from(ToothView view) {
        return new OdontogramSummaryResponse(view.record().getToothFdi(),
                ToothNotation.dentitionOf(view.record().getToothFdi()), view.record().getCondition());
    }
}

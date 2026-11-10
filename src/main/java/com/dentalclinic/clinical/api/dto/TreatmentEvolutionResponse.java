package com.dentalclinic.clinical.api.dto;

import java.time.Instant;

/** Entrada de evolucion de un tratamiento en respuestas (RF-TR-07). */
public record TreatmentEvolutionResponse(
        Long id,
        String content,
        Long authorDentistId,
        String authorName,
        Instant createdAt,
        Long treatmentId,
        ClinicalAppointmentContextResponse appointmentContext,
        Long correctsRecordId,
        String correctionReason) {
}

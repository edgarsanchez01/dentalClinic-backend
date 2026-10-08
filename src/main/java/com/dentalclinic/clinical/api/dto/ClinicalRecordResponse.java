package com.dentalclinic.clinical.api.dto;

import java.time.Instant;

/** Expediente clinico en respuestas (RF-CX-02). */
public record ClinicalRecordResponse(
        Long patientId,
        String medicalBackground,
        String allergies,
        String clinicalHistory,
        String generalObservations,
        Instant updatedAt) {
}
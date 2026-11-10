package com.dentalclinic.clinical.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.dentalclinic.clinical.domain.Treatment;
import com.dentalclinic.clinical.domain.TreatmentStatus;

/** Tratamiento clinico en respuestas (RF-TR-04/05/06). */
public record TreatmentResponse(
        Long id,
        Long patientId,
        Long appointmentId,
        String description,
        String diagnosis,
        TreatmentStatus status,
        String notes,
        BigDecimal price,
        Instant createdAt,
        Instant updatedAt,
        List<Integer> toothFdis) {

    public static TreatmentResponse from(Treatment treatment, List<Integer> toothFdis) {
        return new TreatmentResponse(treatment.getId(), treatment.getPatientId(),
                treatment.getAppointmentId(), treatment.getDescription(), treatment.getDiagnosis(),
                treatment.getStatus(), treatment.getNotes(), treatment.getPrice(),
                treatment.getCreatedAt(), treatment.getUpdatedAt(), toothFdis);
    }
}
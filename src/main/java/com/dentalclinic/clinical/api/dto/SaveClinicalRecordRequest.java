package com.dentalclinic.clinical.api.dto;

/** Contenido editable del expediente clinico (RF-CX-02). */
public record SaveClinicalRecordRequest(
        String medicalBackground,
        String allergies,
        String clinicalHistory,
        String generalObservations) {
}
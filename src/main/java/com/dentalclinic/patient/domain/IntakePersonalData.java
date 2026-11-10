package com.dentalclinic.patient.domain;

import java.time.LocalDate;

/** Snapshot of patient data, not account-holder identity or proof of legal representation. */
public record IntakePersonalData(String firstName, String lastName, LocalDate birthDate,
        String phone, Boolean canProvidePhone, String emergencyContactName,
        String emergencyContactRelationship, String emergencyContactPhone,
        String responsibleAdultName, ResponsibleRelationship responsibleAdultRelationship,
        String responsibleAdultPhone) {
    public enum ResponsibleRelationship { MOTHER, FATHER, LEGAL_GUARDIAN }

    public IntakePersonalData normalized() {
        return new IntakePersonalData(trim(firstName), trim(lastName), birthDate, trim(phone), canProvidePhone,
                trim(emergencyContactName), trim(emergencyContactRelationship), trim(emergencyContactPhone),
                trim(responsibleAdultName), responsibleAdultRelationship, trim(responsibleAdultPhone));
    }
    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    @Override public String toString() { return "IntakePersonalData[redacted]"; }
}

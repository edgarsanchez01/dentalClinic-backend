package com.dentalclinic.medicalintake.domain;

/** Fixed questionnaire v1; no diagnoses, dosages, dates or professional treatments inferred. */
public final class IntakeAnswers {
    private IntakeAnswers() { }

    public enum Answer { YES, NO, UNKNOWN, DISCUSS_IN_CONSULTATION }
    public enum ConfirmedBy { PATIENT, RESPONSIBLE_ADULT }

    public record Question(Answer answer, String details) {
        @Override public String toString() { return "Question[redacted]"; }
    }

    public record Medical(Question allergies, Question medications, Question medicalConditions,
            Question surgeriesOrHospitalizations, Question anesthesiaReactions,
            Question bleedingOrAnticoagulants) {
        @Override public String toString() { return "Medical[redacted]"; }
    }

    public record Dental(Question previousTreatments, Question orthodonticsOrAppliances,
            Question currentDiscomfort, Question gumBleedingOrSensitivity, String consultationReason) {
        @Override public String toString() { return "Dental[redacted]"; }
    }
}

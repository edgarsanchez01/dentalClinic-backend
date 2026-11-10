package com.dentalclinic.medicalintake.domain;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import com.dentalclinic.patient.domain.IntakePersonalData;
import com.dentalclinic.medicalintake.domain.IntakeAnswers.*;

public final class IntakeValidation {
    private IntakeValidation() { }

    public static boolean isMinor(IntakePersonalData data, LocalDate today) {
        return data.birthDate() != null && java.time.Period.between(data.birthDate(), today).getYears() < 18;
    }

    public static void personal(IntakePersonalData p, LocalDate today, boolean complete) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (p == null) { errors.put("personal", "Completa tus datos personales."); fail(errors); }
        text(errors, "firstName", p.firstName(), 100, true);
        text(errors, "lastName", p.lastName(), 100, true);
        if (p.birthDate() != null && (p.birthDate().isAfter(today) || p.birthDate().getYear() < 1))
            errors.put("birthDate", "Introduce una fecha real y no futura.");
        if (complete && p.birthDate() == null) errors.put("birthDate", "La fecha de nacimiento es obligatoria.");
        if (complete && p.canProvidePhone() == null) errors.put("canProvidePhone", "Indica si puedes proporcionar teléfono.");
        text(errors, "phone", p.phone(), 50, complete && Boolean.TRUE.equals(p.canProvidePhone()));
        text(errors, "emergencyContactName", p.emergencyContactName(), 200, complete);
        text(errors, "emergencyContactRelationship", p.emergencyContactRelationship(), 100, complete);
        text(errors, "emergencyContactPhone", p.emergencyContactPhone(), 50, complete);
        boolean minor = isMinor(p, today);
        text(errors, "responsibleAdultName", p.responsibleAdultName(), 200, complete && minor);
        text(errors, "responsibleAdultPhone", p.responsibleAdultPhone(), 50, complete && minor);
        if (complete && minor && p.responsibleAdultRelationship() == null)
            errors.put("responsibleAdultRelationship", "Indica madre, padre o tutor legal.");
        if (p.birthDate() != null && !minor && (present(p.responsibleAdultName())
                || present(p.responsibleAdultPhone()) || p.responsibleAdultRelationship() != null))
            errors.put("responsibleAdultName", "Los datos del responsable solo se solicitan para menores.");
        fail(errors);
    }

    public static void medical(Medical m, boolean complete) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (m == null) { if (complete) errors.put("medical", "Completa los antecedentes médicos."); }
        else {
            question(errors, "allergies", m.allergies(), complete);
            question(errors, "medications", m.medications(), complete);
            question(errors, "medicalConditions", m.medicalConditions(), complete);
            question(errors, "surgeriesOrHospitalizations", m.surgeriesOrHospitalizations(), complete);
            question(errors, "anesthesiaReactions", m.anesthesiaReactions(), complete);
            question(errors, "bleedingOrAnticoagulants", m.bleedingOrAnticoagulants(), complete);
        }
        fail(errors);
    }

    public static void dental(Dental d, boolean complete) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (d == null) { if (complete) errors.put("dental", "Completa los antecedentes dentales."); }
        else {
            question(errors, "previousTreatments", d.previousTreatments(), complete);
            question(errors, "orthodonticsOrAppliances", d.orthodonticsOrAppliances(), complete);
            question(errors, "currentDiscomfort", d.currentDiscomfort(), complete);
            question(errors, "gumBleedingOrSensitivity", d.gumBleedingOrSensitivity(), complete);
            text(errors, "consultationReason", d.consultationReason(), 1000, complete);
        }
        fail(errors);
    }

    private static void question(Map<String, String> errors, String field, Question q, boolean complete) {
        if (q == null || q.answer() == null) {
            if (complete || q != null) errors.put(field + ".answer", "Selecciona una respuesta.");
        }
        if (q != null) {
            text(errors, field + ".details", q.details(), 2000, false);
            if (present(q.details()) && q.answer() != Answer.YES)
                errors.put(field + ".details", "Los detalles solo se guardan con respuesta YES.");
        }
    }

    private static void text(Map<String, String> errors, String field, String value, int max, boolean required) {
        if (required && !present(value)) errors.put(field, "Este dato es obligatorio.");
        if (value != null && (value.length() > max || value.indexOf('\0') >= 0))
            errors.put(field, "Revisa la longitud y el formato del dato.");
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static void fail(Map<String, String> errors) {
        if (!errors.isEmpty()) throw new IntakeException("INTAKE_VALIDATION_FAILED", "Revisa los datos del cuestionario.", errors);
    }
}

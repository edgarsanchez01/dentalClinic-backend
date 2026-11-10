package com.dentalclinic.patient.domain;

public class MedicalOnboardingRequiredException extends RuntimeException {
    public MedicalOnboardingRequiredException() {
        super("Completa tu cuestionario médico antes de reservar o reprogramar una cita.");
    }
}

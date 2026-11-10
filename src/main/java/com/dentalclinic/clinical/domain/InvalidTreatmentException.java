package com.dentalclinic.clinical.domain;

/** Datos invalidos para un tratamiento clinico. La capa API lo traduce a 400. */
public class InvalidTreatmentException extends RuntimeException {

    public InvalidTreatmentException(String message) {
        super(message);
    }
}

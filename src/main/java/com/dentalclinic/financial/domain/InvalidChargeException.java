package com.dentalclinic.financial.domain;

/** Se lanza cuando un cargo viola una invariante de dominio. */
public class InvalidChargeException extends RuntimeException {
    public InvalidChargeException(String message) {
        super(message);
    }
}

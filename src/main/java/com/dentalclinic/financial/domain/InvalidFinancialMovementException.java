package com.dentalclinic.financial.domain;

/** Se lanza cuando un movimiento financiero viola una invariante de dominio. */
public class InvalidFinancialMovementException extends RuntimeException {
    public InvalidFinancialMovementException(String message) {
        super(message);
    }
}

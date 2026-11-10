package com.dentalclinic.financial.domain;

/** Conflicto financiero (saldo, estado, reversa o clave reutilizada); HTTP 409 en Fase 3. */
public class FinancialConflictException extends RuntimeException {
    public FinancialConflictException(String message) {
        super(message);
    }
}

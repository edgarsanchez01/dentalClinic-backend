package com.dentalclinic.financial.domain;

/** Conserva el contrato FinancialConflictException/409; B3 requiere ademas prueba de rollback. */
public final class DefinitiveFinancialConflictException extends FinancialConflictException {
    private final FinancialRejectionReason reason;

    public DefinitiveFinancialConflictException(FinancialRejectionReason reason, String message) {
        super(message);
        if (reason.httpStatus() != 409) throw new IllegalArgumentException("Causa de conflicto incompatible.");
        this.reason = reason;
    }

    public FinancialRejectionReason reason() { return reason; }
}

package com.dentalclinic.financial.domain;

/** Conserva el contrato InvalidChargeException/400; no basta por si sola para cerrar una intencion. */
public final class DefinitiveInvalidChargeException extends InvalidChargeException {
    private final FinancialRejectionReason reason;

    public DefinitiveInvalidChargeException(FinancialRejectionReason reason, String message) {
        super(message);
        if (reason.httpStatus() != 400) throw new IllegalArgumentException("Causa de cargo incompatible.");
        this.reason = reason;
    }

    public FinancialRejectionReason reason() { return reason; }
}

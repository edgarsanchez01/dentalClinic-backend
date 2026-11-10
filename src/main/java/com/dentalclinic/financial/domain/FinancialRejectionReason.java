package com.dentalclinic.financial.domain;

/** Allowlist B3 de reglas de negocio existentes; no clasificar por mensaje ni por HTTP generico. */
public enum FinancialRejectionReason {
    CHARGE_ITEMS_REQUIRED(FinancialIntentOperation.FIN_CONFIRM_CHARGE, 400),
    CHARGE_CANCELLED(FinancialIntentOperation.FIN_REGISTER_PAYMENT, 409),
    PAYMENT_EXCEEDS_BALANCE(FinancialIntentOperation.FIN_REGISTER_PAYMENT, 409),
    PAYMENT_ALREADY_REVERSED(FinancialIntentOperation.FIN_REVERSE_PAYMENT, 409),
    CHARGE_ALREADY_CANCELLED(FinancialIntentOperation.FIN_CANCEL_CHARGE, 409),
    CHARGE_HAS_PAYMENTS(FinancialIntentOperation.FIN_CANCEL_CHARGE, 409);

    private final FinancialIntentOperation operation;
    private final int httpStatus;

    FinancialRejectionReason(FinancialIntentOperation operation, int httpStatus) {
        this.operation = operation;
        this.httpStatus = httpStatus;
    }

    public FinancialIntentOperation operation() { return operation; }
    public int httpStatus() { return httpStatus; }
}

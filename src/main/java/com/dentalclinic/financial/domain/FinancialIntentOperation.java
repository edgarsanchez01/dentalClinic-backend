package com.dentalclinic.financial.domain;

/** Allowlist AD-34; los nombres son las operaciones de http_idempotency existentes. */
public enum FinancialIntentOperation {
    FIN_CONFIRM_CHARGE,
    FIN_REGISTER_PAYMENT,
    FIN_REVERSE_PAYMENT,
    FIN_CANCEL_CHARGE
}

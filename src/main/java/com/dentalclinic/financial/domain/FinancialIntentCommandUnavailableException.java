package com.dentalclinic.financial.domain;

/** B1 no conserva representacion suficiente; no es conflicto de negocio ni rechazo durable. */
public class FinancialIntentCommandUnavailableException extends IllegalStateException {
    public FinancialIntentCommandUnavailableException() {
        super("INTENT_COMMAND_UNAVAILABLE: el comando B1 no reproduce su hash original; "
                + "se requiere el comando original, sin inferir ni crear otra identidad.");
    }
}

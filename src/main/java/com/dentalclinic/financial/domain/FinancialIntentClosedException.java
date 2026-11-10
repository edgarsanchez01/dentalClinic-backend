package com.dentalclinic.financial.domain;

/** B2 respeta cierres; no crea ni modifica REJECTED/ABANDONED (B3). */
public class FinancialIntentClosedException extends FinancialConflictException {
    public FinancialIntentClosedException() {
        super("INTENT_CLOSED: la intencion esta cerrada sin efecto y no se puede ejecutar.");
    }
}

package com.dentalclinic.financial.domain;

/** Identidad ya registrada con otro comando/version. El transporte HTTP pertenece a B4. */
public class FinancialIntentCommandConflictException extends FinancialConflictException {
    public FinancialIntentCommandConflictException() {
        super("INTENT_COMMAND_CONFLICT: la identidad ya tiene un comando diferente.");
    }
}

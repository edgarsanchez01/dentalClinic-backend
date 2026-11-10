package com.dentalclinic.financial.domain;

/** Cualquier recibo, incluso incompatible/ilegible, impide declarar cierre sin efecto. */
public final class FinancialIntentClosureBlockedException extends FinancialConflictException {
    public FinancialIntentClosureBlockedException() {
        super("INTENT_HAS_RECEIPT: no se puede cerrar sin efecto; verificar el resultado registrado.");
    }
}

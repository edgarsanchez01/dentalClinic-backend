package com.dentalclinic.financial.domain;

/** No prometer REJECTED/OPEN tras un fallo de finalizacion o de su confirmacion de commit. */
public final class FinancialIntentClosureUncertainException extends IllegalStateException {
    public FinancialIntentClosureUncertainException() {
        super("INTENT_CLOSURE_UNCERTAIN: no se pudo acreditar el cierre; verificar el estado antes de reanudar.");
    }
}

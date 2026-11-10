package com.dentalclinic.financial.domain;

/** El checkout no puede elegir un cargo cuando hay mas de uno activo. */
public class CheckoutChargeAmbiguousException extends FinancialConflictException {

    public CheckoutChargeAmbiguousException() {
        super("La cita tiene varios cargos activos; el checkout no puede elegir uno.");
    }
}

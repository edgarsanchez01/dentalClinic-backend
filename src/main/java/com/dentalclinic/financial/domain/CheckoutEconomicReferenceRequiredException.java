package com.dentalclinic.financial.domain;

/** La cita carece de un snapshot economico completo y representable para crear el cargo. */
public class CheckoutEconomicReferenceRequiredException extends FinancialConflictException {

    public CheckoutEconomicReferenceRequiredException() {
        super("La cita no tiene un snapshot economico completo y utilizable.");
    }
}

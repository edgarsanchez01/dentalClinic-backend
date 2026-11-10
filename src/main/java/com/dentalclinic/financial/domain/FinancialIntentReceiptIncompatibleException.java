package com.dentalclinic.financial.domain;

/** Fallo cerrado de verificacion; no afirma ausencia de efecto ni habilita una nueva ejecucion. */
public final class FinancialIntentReceiptIncompatibleException extends RuntimeException {
    public FinancialIntentReceiptIncompatibleException() {
        super("No se pudo verificar el recibo de esta intencion. Verifica la misma identidad antes de reanudar.");
    }
}

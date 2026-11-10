package com.dentalclinic.financial.domain;

/**
 * El pago solicitado por el cierre integrado supera el saldo pendiente del cargo seleccionado.
 *
 * <p>El checkout la traduce a {@code CHECKOUT_PAYMENT_EXCEEDS_BALANCE}. Es un conflicto estable de
 * negocio, no un fallo tecnico ni una invitacion a reintentar con otra clave.
 */
public class CheckoutPaymentExceedsBalanceException extends FinancialConflictException {

    public CheckoutPaymentExceedsBalanceException() {
        super("El pago supera el saldo pendiente del cargo.");
    }
}

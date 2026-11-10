package com.dentalclinic.checkout.application;

import java.math.BigDecimal;

import com.dentalclinic.checkout.domain.ChargeAction;
import com.dentalclinic.checkout.domain.PaymentAction;
import com.dentalclinic.financial.domain.PaymentMethod;

/** Comando interno ya validado sintacticamente para el coordinador de checkout. */
public record CheckoutCommand(Long appointmentId, int expectedOccurrenceVersion,
        String financialContextToken, ChargeAction chargeAction, Long chargeId,
        BigDecimal expectedTotal, PaymentAction paymentAction, PaymentMethod paymentMethod,
        BigDecimal paymentAmount, String idempotencyKey) {

    public CheckoutCommand {
        if (appointmentId == null || appointmentId <= 0 || expectedOccurrenceVersion < 1
                || financialContextToken == null || !financialContextToken.matches("v1:[0-9a-f]{64}")
                || idempotencyKey == null || !idempotencyKey.matches("[\\x21-\\x7E]{1,200}")
                || chargeAction == null || paymentAction == null || !validMoney(expectedTotal, false)
                || (chargeAction == ChargeAction.CREATE_FROM_APPOINTMENT && chargeId != null)
                || (chargeAction == ChargeAction.REUSE && (chargeId == null || chargeId <= 0))
                || (paymentAction == PaymentAction.REGISTER
                        && (paymentMethod == null || !validMoney(paymentAmount, true)))
                || (paymentAction == PaymentAction.NO_NEW_PAYMENT
                        && (paymentMethod != null || paymentAmount != null))) {
            throw new com.dentalclinic.checkout.domain.CheckoutException(
                    com.dentalclinic.checkout.domain.CheckoutErrorCode.CHECKOUT_INVALID_REQUEST,
                    "La peticion de cierre no cumple el contrato.");
        }
    }

    private static boolean validMoney(BigDecimal amount, boolean positive) {
        if (amount == null || amount.signum() < 0 || (positive && amount.signum() == 0)) return false;
        var value = amount.stripTrailingZeros();
        return value.scale() <= 2 && value.precision() - value.scale() <= 15;
    }
}

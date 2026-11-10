package com.dentalclinic.financial.application;

import java.math.BigDecimal;
import com.dentalclinic.financial.application.ContextualCheckoutFinancialParticipant.ChargeInstruction;
import com.dentalclinic.financial.application.ContextualCheckoutFinancialParticipant.PaymentInstruction;
import com.dentalclinic.financial.application.ContextualCheckoutFinancialParticipant.ChargeAction;
import com.dentalclinic.financial.application.ContextualCheckoutFinancialParticipant.PaymentAction;
import com.dentalclinic.financial.domain.InvalidFinancialMovementException;

/** Typed administrative command. Validation applies to HTTP and direct application callers. */
public record CollectionCommand(Long appointmentId, int expectedOccurrenceVersion,
        ChargeInstruction charge, PaymentInstruction payment, String idempotencyKey) {
    public CollectionCommand {
        if (appointmentId == null || appointmentId <= 0 || expectedOccurrenceVersion < 1
                || charge == null || payment == null || charge.action() == null
                || charge.financialContextToken() == null
                || !charge.financialContextToken().matches("v1:[0-9a-f]{64}")
                || !money(charge.expectedTotal()) || charge.expectedTotal().signum() <= 0
                || payment.action() == null
                || idempotencyKey == null || !idempotencyKey.matches("[\\x21-\\x7E]{1,200}")) invalid();
        if (charge.action() == ChargeAction.CREATE_FROM_APPOINTMENT && charge.chargeId() != null) invalid();
        if (charge.action() == ChargeAction.REUSE && (charge.chargeId() == null || charge.chargeId() <= 0)) invalid();
        if (payment.action() == PaymentAction.REGISTER) {
            if (payment.method() == null || !money(payment.amount()) || payment.amount().signum() <= 0) invalid();
        } else if (payment.method() != null || payment.amount() != null) invalid();
        // Equivalent exact decimals have the same durable fingerprint. Never round.
        charge = new ChargeInstruction(charge.action(), charge.chargeId(),
                charge.expectedTotal().stripTrailingZeros(), charge.financialContextToken());
        payment = new PaymentInstruction(payment.action(), payment.method(),
                payment.amount() == null ? null : payment.amount().stripTrailingZeros());
    }

    private static boolean money(BigDecimal value) {
        if (value == null) return false;
        var stripped = value.stripTrailingZeros();
        return stripped.scale() <= 2 && stripped.precision() - stripped.scale() <= 15;
    }

    public static void invalid() {
        throw new InvalidFinancialMovementException("La solicitud de cobro no cumple el contrato.");
    }
}

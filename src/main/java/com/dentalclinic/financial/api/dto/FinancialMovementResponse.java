package com.dentalclinic.financial.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.dentalclinic.financial.domain.FinancialMovement;
import com.dentalclinic.financial.domain.FinancialMovementType;
import com.dentalclinic.financial.domain.PaymentMethod;

public record FinancialMovementResponse(Long id, Long chargeId, Long patientId, FinancialMovementType type,
        BigDecimal amount, PaymentMethod paymentMethod, Long reversesMovementId, String reason,
        Long performedByUserId, Instant performedAt, Instant createdAt) {
    public static FinancialMovementResponse from(FinancialMovement movement) {
        return new FinancialMovementResponse(movement.getId(), movement.getChargeId(), movement.getPatientId(),
                movement.getType(), movement.getAmount(), movement.getPaymentMethod(), movement.getReversesMovementId(),
                movement.getReason(), movement.getPerformedByUserId(), movement.getPerformedAt(), movement.getCreatedAt());
    }
}

package com.dentalclinic.financial.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.dentalclinic.financial.domain.Charge;

/** Resultado original de una escritura; no se recompone con saldos actuales durante un replay. */
public record ChargeReceiptResponse(Long id, Long patientId, Long appointmentId, String currency,
        BigDecimal totalAmount, String notes, Instant confirmedAt, Long confirmedByUserId,
        Instant cancelledAt, Long cancelledByUserId, String cancellationReason,
        int version, Instant createdAt, Instant updatedAt) {
    public static ChargeReceiptResponse from(Charge charge) {
        return new ChargeReceiptResponse(charge.getId(), charge.getPatientId(), charge.getAppointmentId(),
                charge.getCurrency(), charge.getTotalAmount(), charge.getNotes(), charge.getConfirmedAt(),
                charge.getConfirmedByUserId(), charge.getCancelledAt(), charge.getCancelledByUserId(),
                charge.getCancellationReason(), charge.getVersion(), charge.getCreatedAt(), charge.getUpdatedAt());
    }
}

package com.dentalclinic.financial.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.dentalclinic.financial.application.ChargeQueryService.ChargeDetail;
import com.dentalclinic.financial.application.ChargeQueryService.ChargeView;
import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.ChargeFinancialState;
import com.dentalclinic.financial.domain.ChargeStatus;

/** Estado actual de lectura. Listados sin historial; detalle con conceptos y movimientos historicos. */
public record ChargeResponse(Long id, Long patientId, Long appointmentId, String currency, String notes,
        BigDecimal totalAmount, BigDecimal appliedAmount, BigDecimal pendingBalance, ChargeStatus status,
        Instant confirmedAt, Long confirmedByUserId, Instant cancelledAt, Long cancelledByUserId,
        String cancellationReason, int version, Instant createdAt, Instant updatedAt,
        List<ChargeItemResponse> items, List<FinancialMovementResponse> movements) {

    public static ChargeResponse summary(ChargeView view) {
        return from(view, null, null);
    }

    public static ChargeResponse detail(ChargeDetail detail) {
        return from(detail.view(), detail.items().stream().map(ChargeItemResponse::from).toList(),
                detail.movements().stream().map(FinancialMovementResponse::from).toList());
    }

    private static ChargeResponse from(ChargeView view, List<ChargeItemResponse> items,
                                        List<FinancialMovementResponse> movements) {
        Charge charge = view.charge();
        ChargeFinancialState state = view.financialState();
        return new ChargeResponse(charge.getId(), charge.getPatientId(), charge.getAppointmentId(),
                charge.getCurrency(), charge.getNotes(), state.totalAmount(), state.appliedAmount(),
                state.pendingBalance(), state.status(), charge.getConfirmedAt(), charge.getConfirmedByUserId(),
                charge.getCancelledAt(), charge.getCancelledByUserId(), charge.getCancellationReason(),
                charge.getVersion(), charge.getCreatedAt(), charge.getUpdatedAt(), items, movements);
    }
}

package com.dentalclinic.financial.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.dentalclinic.financial.application.AppointmentFinancialContextService.FinancialContextCharge;
import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.ChargeFinancialState;
import com.dentalclinic.financial.domain.ChargeStatus;

/**
 * Un cargo dentro del contexto financiero de una atencion.
 *
 * <p>Expone el cargo con su estado <strong>derivado</strong> ({@code PENDIENTE}/{@code PARCIAL}/
 * {@code PAGADO}/{@code ANULADO}), su total, el monto aplicado y el saldo pendiente. Los tres
 * importes son <strong>de este cargo</strong>: la respuesta no agrega saldos entre cargos, porque
 * un total agregado seria una cifra que el backend no puede sostener (C-04).
 *
 * <p>Los conceptos ({@link ChargeItemResponse}) y los movimientos ({@link FinancialMovementResponse})
 * son los historicos ya existentes: no se recalculan ni se reinterpretan.
 */
public record FinancialContextChargeResponse(Long id, Long patientId, Long appointmentId, String currency,
        String notes, BigDecimal totalAmount, BigDecimal appliedAmount, BigDecimal pendingBalance,
        ChargeStatus status, Instant confirmedAt, Long confirmedByUserId, Instant cancelledAt,
        Long cancelledByUserId, String cancellationReason, List<ChargeItemResponse> items,
        List<FinancialMovementResponse> movements) {

    public static FinancialContextChargeResponse from(FinancialContextCharge context) {
        Charge charge = context.charge();
        ChargeFinancialState state = context.state();
        return new FinancialContextChargeResponse(charge.getId(), charge.getPatientId(),
                charge.getAppointmentId(), charge.getCurrency(), charge.getNotes(), state.totalAmount(),
                state.appliedAmount(), state.pendingBalance(), state.status(), charge.getConfirmedAt(),
                charge.getConfirmedByUserId(), charge.getCancelledAt(), charge.getCancelledByUserId(),
                charge.getCancellationReason(), context.items().stream().map(ChargeItemResponse::from).toList(),
                context.movements().stream().map(FinancialMovementResponse::from).toList());
    }
}

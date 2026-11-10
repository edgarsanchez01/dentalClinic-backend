package com.dentalclinic.financial.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.dentalclinic.financial.application.FinancialReportService.ChargesBalance;
import com.dentalclinic.financial.domain.ChargeStatus;

/**
 * C. Cargos y saldos con filtros administrativos y desglose por paciente (estado actual).
 *
 * <p>Totales y desglose respetan los mismos filtros. Los montos de cartera son vigentes
 * (los cargos anulados no facturan); los saldos son por estado actual, no un corte histórico.
 *
 * @param patientId       eco del filtro de paciente; {@code null} si no se filtró
 * @param status          eco del filtro de estado; {@code null} si no se filtró
 * @param from            eco del filtro de fecha de confirmación (inclusive); {@code null} si no
 * @param to              eco del filtro de fecha de confirmación (exclusivo); {@code null} si no
 * @param byPatient       desglose por paciente ordenado por saldo pendiente descendente
 */
public record ChargesBalanceReportResponse(Long patientId, ChargeStatus status, Instant from, Instant to,
        long confirmedCharges, BigDecimal billedAmount, BigDecimal appliedAmount, BigDecimal pendingBalance,
        List<PatientBalance> byPatient) {

    public record PatientBalance(Long patientId, long chargeCount, BigDecimal billedAmount,
                                 BigDecimal appliedAmount, BigDecimal pendingBalance) { }

    public static ChargesBalanceReportResponse from(Long patientId, ChargeStatus status, Instant from,
                                                     Instant to, ChargesBalance balance) {
        List<PatientBalance> byPatient = balance.byPatient().stream()
                .map(patient -> new PatientBalance(patient.patientId(), patient.chargeCount(),
                        patient.billedAmount(), patient.appliedAmount(), patient.pendingBalance()))
                .toList();
        return new ChargesBalanceReportResponse(patientId, status, from, to, balance.confirmedCharges(),
                balance.billedAmount(), balance.appliedAmount(), balance.pendingBalance(), byPatient);
    }
}

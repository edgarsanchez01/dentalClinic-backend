package com.dentalclinic.financial.domain;

import java.math.BigDecimal;

/**
 * Calculo <strong>puro</strong> del estado financiero de un cargo.
 *
 * <p>Semantica de {@code appliedAmount} (fijada aqui y en
 * {@code FinancialMovementRepository#sumValidAppliedAmount}): es la suma de movimientos
 * {@code PAYMENT} cuyo {@code id} NO es referenciado como {@code reversesMovementId} por ninguna
 * {@code REVERSAL}. Por tanto:
 *
 * <ul>
 *   <li>un pago ya revertido deja de contar;</li>
 *   <li>una {@code REVERSAL} NUNCA suma como ingreso (su tipo no es {@code PAYMENT});</li>
 *   <li>un movimiento no se cuenta dos veces.</li>
 * </ul>
 *
 * <p>Reglas de calculo, sin tolerancias ni epsilon:
 *
 * <ul>
 *   <li>{@code appliedAmount > totalAmount} es sobrepago y se rechaza con
 *       {@link InvalidChargeException} (RF-PY-07; 409 se decide en la capa HTTP de Fase 2B).</li>
 *   <li>{@code appliedAmount == 0} &rarr; {@link ChargeStatus#PENDIENTE}.</li>
 *   <li>{@code 0 < appliedAmount < total} &rarr; {@link ChargeStatus#PARCIAL}.</li>
 *   <li>{@code appliedAmount == total} &rarr; {@link ChargeStatus#PAGADO}.</li>
 *   <li>Cargo anulado &rarr; {@link ChargeStatus#ANULADO} y saldo pendiente {@code 0}. Anular un
 *       cargo con pagos validos se impide en escritura (Fase 2B); si hubiera pagos validos en un
 *       cargo anulado se rechaza la inconsistencia, no se oculta el saldo.</li>
 * </ul>
 *
 * <p>La comparacion monetaria usa {@link BigDecimal#compareTo(BigDecimal)} (ignora la escala), de
 * modo que {@code 100.0000} y {@code 100} se consideran iguales sin redondear.
 */
public final class ChargeStatusCalculator {

    private ChargeStatusCalculator() {
    }

    public static ChargeFinancialState evaluate(BigDecimal totalAmount, BigDecimal appliedAmount,
                                                boolean cancelled) {
        BigDecimal total = FinancialAmounts.requirePositive(
                totalAmount, "totalAmount", InvalidChargeException::new);
        BigDecimal applied = FinancialAmounts.requireNonNegative(
                appliedAmount, "appliedAmount", InvalidChargeException::new);
        if (applied.compareTo(total) > 0) {
            throw new InvalidChargeException(
                    "el monto aplicado no puede superar el total del cargo (sobrepago)");
        }
        if (cancelled) {
            if (applied.signum() != 0) {
                throw new InvalidChargeException("un cargo anulado no puede conservar pagos validos");
            }
            return new ChargeFinancialState(total, applied, BigDecimal.ZERO, ChargeStatus.ANULADO);
        }
        ChargeStatus status;
        if (applied.signum() == 0) {
            status = ChargeStatus.PENDIENTE;
        } else if (applied.compareTo(total) < 0) {
            status = ChargeStatus.PARCIAL;
        } else {
            status = ChargeStatus.PAGADO;
        }
        return new ChargeFinancialState(total, applied, total.subtract(applied), status);
    }

}

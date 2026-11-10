package com.dentalclinic.financial.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Fotografia financiera derivada de un {@link Charge}: total, monto aplicado, saldo pendiente y
 * estado de liquidacion.
 *
 * <p>Es un valor inmutable de solo lectura; nunca se persiste. {@code appliedAmount} es la suma de
 * abonos VALIDOS (pagos no revertidos) y {@code pendingBalance = totalAmount - appliedAmount};
 * jamas es negativo porque no se admite sobrepago (se rechaza con
 * {@link InvalidChargeException}).
 *
 * @param totalAmount    importe total positivo del cargo
 * @param appliedAmount  suma de abonos validos no revertidos, no negativa y no mayor al total
 * @param pendingBalance saldo pendiente ({@code total - aplicado}); {@code 0} si el cargo esta
 *                       anulado
 * @param status         estado de liquidacion derivado
 */
public record ChargeFinancialState(BigDecimal totalAmount, BigDecimal appliedAmount,
                                   BigDecimal pendingBalance, ChargeStatus status) {

    public ChargeFinancialState {
        Objects.requireNonNull(totalAmount, "totalAmount es obligatorio");
        Objects.requireNonNull(appliedAmount, "appliedAmount es obligatorio");
        Objects.requireNonNull(pendingBalance, "pendingBalance es obligatorio");
        Objects.requireNonNull(status, "status es obligatorio");
    }
}

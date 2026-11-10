package com.dentalclinic.financial.domain;

/**
 * Tipo de movimiento financiero del MVP.
 *
 * <ul>
 *   <li>{@code PAYMENT}: abono/pago parcial registrado manualmente por administracion.</li>
 *   <li>{@code REVERSAL}: compensacion que revierte un {@code PAYMENT} previo.</li>
 * </ul>
 *
 * <p>Los tipos {@code ADJUSTMENT} y {@code REFUND} (ajustes monetarios y devoluciones
 * fisicas de dinero) quedan FUERA del MVP y no se modelan todavia.
 */
public enum FinancialMovementType {
    PAYMENT,
    REVERSAL
}

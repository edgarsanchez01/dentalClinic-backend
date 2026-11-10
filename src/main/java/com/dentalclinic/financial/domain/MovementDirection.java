package com.dentalclinic.financial.domain;

/**
 * Direccion del movimiento sobre el saldo del cargo.
 *
 * <ul>
 *   <li>{@code IN}: entrada de dinero ({@link FinancialMovementType#PAYMENT}).</li>
 *   <li>{@code OUT}: salida/compensacion ({@link FinancialMovementType#REVERSAL}).</li>
 * </ul>
 */
public enum MovementDirection {
    IN,
    OUT
}

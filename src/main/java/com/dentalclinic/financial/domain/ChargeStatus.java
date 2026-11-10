package com.dentalclinic.financial.domain;

/**
 * Estado de liquidacion <strong>derivado</strong> de un {@link Charge}.
 *
 * <p>No se persiste ni se almacena: es una proyeccion calculada por
 * {@link ChargeStatusCalculator} a partir de {@code totalAmount} y de los movimientos
 * validos. Evitar una segunda fuente de verdad es una decision aprobada (RF-PY-05/06).
 *
 * <ul>
 *   <li>{@code PENDIENTE}: el cargo no tiene ningun abono valido aplicado.</li>
 *   <li>{@code PARCIAL}: tiene abonos validos pero menores al total.</li>
 *   <li>{@code PAGADO}: los abonos validos igualan el total.</li>
 *   <li>{@code ANULADO}: el cargo fue anulado de forma explicita
 *       ({@code cancelledAt != null}); la obligacion queda sin efecto.</li>
 * </ul>
 */
public enum ChargeStatus {
    PENDIENTE,
    PARCIAL,
    PAGADO,
    ANULADO
}

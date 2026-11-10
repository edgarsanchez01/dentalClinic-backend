package com.dentalclinic.financial.domain;

import java.math.BigDecimal;
import java.util.function.Function;

/**
 * Reglas monetarias del MVP (compartidas por {@link Charge}, {@link ChargeItem} y
 * {@link FinancialMovement}).
 *
 * <p>La base almacena con {@code NUMERIC(19,4)} (precision tecnica, igual que V007/V009).
 * La politica de negocio aprobada es de <strong>dos decimales</strong>; se aplica aqui en el
 * dominio para no depender de redondeo silencioso: se rechaza cualquier importe con mas de
 * dos decimales en lugar de redondearlo.
 *
 * <p>Moneda unica MXN; no hay calculo de impuestos.
 */
final class FinancialAmounts {

    static final int BUSINESS_SCALE = 2;
    // Limite de almacenamiento de NUMERIC(19,4), expresado con los dos decimales del MVP.
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999999.99");

    private FinancialAmounts() {
    }

    /** Exige importe estrictamente positivo con a lo sumo dos decimales. */
    static BigDecimal requirePositive(BigDecimal amount, String field,
                                      Function<String, RuntimeException> exceptionFactory) {
        if (amount == null) {
            throw exceptionFactory.apply(field + " es obligatorio");
        }
        if (amount.signum() <= 0) {
            throw exceptionFactory.apply(field + " debe ser estrictamente positivo");
        }
        requireScale(amount, field, exceptionFactory);
        return amount;
    }

    /** Exige importe no negativo con a lo sumo dos decimales. */
    static BigDecimal requireNonNegative(BigDecimal amount, String field,
                                         Function<String, RuntimeException> exceptionFactory) {
        if (amount == null) {
            throw exceptionFactory.apply(field + " es obligatorio");
        }
        if (amount.signum() < 0) {
            throw exceptionFactory.apply(field + " no puede ser negativo");
        }
        requireScale(amount, field, exceptionFactory);
        return amount;
    }

    private static void requireScale(BigDecimal amount, String field,
                                     Function<String, RuntimeException> exceptionFactory) {
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw exceptionFactory.apply(field + " supera el importe maximo admitido");
        }
        if (amount.stripTrailingZeros().scale() > BUSINESS_SCALE) {
            throw exceptionFactory.apply(
                    field + " admite como maximo " + BUSINESS_SCALE + " decimales");
        }
    }
}

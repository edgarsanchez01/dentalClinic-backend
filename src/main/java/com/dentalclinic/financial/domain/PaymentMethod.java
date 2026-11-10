package com.dentalclinic.financial.domain;

/**
 * Metodo del pago registrado manualmente.
 *
 * <p>Son <strong>registros manuales</strong> de cobros recibidos externamente: el sistema
 * NO procesa tarjetas, transferencias ni pasarelas. Solo categoriza el medio del ingreso.
 */
public enum PaymentMethod {
    CASH,
    CARD,
    TRANSFER,
    OTHER
}

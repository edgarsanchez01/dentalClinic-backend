package com.dentalclinic.checkout.domain;

/**
 * El conjunto cita/cargo/movimiento no cumple las invariantes cruzadas del cierre.
 *
 * <p>Las FKs de {@code appointment_checkout} garantizan existencia y unicidad basicas, pero no
 * que cita, cargo y movimiento pertenezcan al mismo paciente ni que el movimiento sea un
 * {@code PAYMENT}. Esa comprobacion se hace antes de persistir, dentro de la transaccion ambiente.
 */
public class CheckoutIntegrityException extends RuntimeException {

    public CheckoutIntegrityException(String message) {
        super(message);
    }
}

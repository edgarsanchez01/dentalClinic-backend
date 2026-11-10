package com.dentalclinic.checkout.domain;

/**
 * Error de negocio estable del cierre integrado. El endpoint lo traduce a un codigo y un estado
 * HTTP; no se usa para fallos tecnicos ni para decidir si la transaccion debe continuar.
 */
public class CheckoutException extends RuntimeException {

    private final CheckoutErrorCode code;

    public CheckoutException(CheckoutErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public CheckoutException(CheckoutErrorCode code) {
        this(code, code.name());
    }

    public CheckoutErrorCode code() {
        return code;
    }
}

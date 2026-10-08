package com.dentalclinic.identity.domain.exception;

/**
 * La cuenta existe pero no está activada ({@code active = false}).
 *
 * <p>Es el kill-switch de P-02: desactiva el acceso del usuario, y con él todas sus
 * familias de refresh. Se traduce a 401 porque es un problema de autenticación.
 */
public class AccountInactiveException extends RuntimeException {

    public AccountInactiveException() {
        super("La cuenta no está activada.");
    }
}

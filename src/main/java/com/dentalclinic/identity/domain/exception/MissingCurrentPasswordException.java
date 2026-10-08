package com.dentalclinic.identity.domain.exception;

/**
 * Cambio de contraseña autenticado sin la contraseña actual (RF-IA-11). Se traduce a 400:
 * es un requisito de la petición, no una regla de negocio.
 */
public class MissingCurrentPasswordException extends RuntimeException {

    public MissingCurrentPasswordException() {
        super("Indica tu contraseña actual para confirmar el cambio.");
    }
}

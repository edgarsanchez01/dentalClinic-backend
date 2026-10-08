package com.dentalclinic.identity.domain.exception;

/**
 * Credenciales no válidas. El mensaje es deliberadamente genérico: no distingue entre
 * "el email no existe" y "la contraseña no coincide", para no revelar qué emails están
 * registrados (Architecture.md §7.7, P-03 CLOSED).
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("El correo electrónico o la contraseña no son correctos.");
    }
}

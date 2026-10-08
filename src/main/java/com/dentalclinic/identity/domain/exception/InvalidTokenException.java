package com.dentalclinic.identity.domain.exception;

/**
 * Token de invitación, de activación o de recuperación inválido, expirado o ya usado.
 *
 * <p>El mensaje es intencionadamente idéntico para todos esos casos: no se confirma si el
 * token existió (Architecture.md §7.1, §7.7).
 */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException() {
        super("El enlace no es válido o ha expirado.");
    }
}

package com.dentalclinic.identity.domain.exception;

/**
 * Acción prohibida con identidad válida (403). A diferencia de {@code NotFoundException} (404, "no
 * existe o no es tuyo"), aquí el actor existe, está autenticado y tiene el rol, pero la acción está
 * fuera de su alcance (p. ej. reservar una cita para otro profesional, §8.2).
 */
public class ForbiddenOperationException extends RuntimeException {

    public ForbiddenOperationException(String message) {
        super(message);
    }
}
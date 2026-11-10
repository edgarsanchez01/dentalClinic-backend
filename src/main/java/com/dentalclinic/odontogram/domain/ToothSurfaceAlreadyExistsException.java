package com.dentalclinic.odontogram.domain;

/**
 * Ya existe un registro vigente para la superficie indicada de la pieza. La capa API lo traduce a
 * 409: cada superficie admite un único registro (el estado actual), que se modifica con {@code PUT}.
 */
public class ToothSurfaceAlreadyExistsException extends RuntimeException {

    public ToothSurfaceAlreadyExistsException(String message) {
        super(message);
    }
}
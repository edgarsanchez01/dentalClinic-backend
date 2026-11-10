package com.dentalclinic.odontogram.domain;

/**
 * Ya existe un registro vigente para la pieza indicada. La capa API lo traduce a 409: la pieza
 * admite un único registro (el estado actual), que se modifica con {@code PUT}.
 */
public class ToothRecordAlreadyExistsException extends RuntimeException {

    public ToothRecordAlreadyExistsException(String message) {
        super(message);
    }
}

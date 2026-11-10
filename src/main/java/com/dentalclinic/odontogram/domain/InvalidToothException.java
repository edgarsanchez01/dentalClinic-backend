package com.dentalclinic.odontogram.domain;

/** Datos inválidos de una pieza del odontograma. La capa API lo traduce a 400. */
public class InvalidToothException extends RuntimeException {

    public InvalidToothException(String message) {
        super(message);
    }
}

package com.dentalclinic.scheduling.domain.exception;

/**
 * Una entrada de Scheduling no es válida por sí misma (400): franja con {@code start >= end}, break
 * fuera de la franja, horas incoherentes en una franquicia, día fuera de ISO 1..7, etc.
 */
public class InvalidScheduleException extends RuntimeException {

    public InvalidScheduleException(String message) {
        super(message);
    }
}
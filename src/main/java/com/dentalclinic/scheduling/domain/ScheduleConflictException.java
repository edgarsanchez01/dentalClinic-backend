package com.dentalclinic.scheduling.domain;

/**
 * La operación es válida en forma pero entra en conflicto con una invariante de Scheduling (409):
 * el horario de un dentista queda fuera de la ventana de la clínica, dos breaks solapan, o una
 * excepción de dentista intenta abrir con la clínica cerrada.
 */
public class ScheduleConflictException extends RuntimeException {

    public ScheduleConflictException(String message) {
        super(message);
    }
}
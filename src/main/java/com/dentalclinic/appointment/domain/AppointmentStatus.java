package com.dentalclinic.appointment.domain;

/**
 * Estado de una cita (RF-AP-02/03): exactamente cuatro estados, ninguno auxiliar.
 *
 * <p>Solo {@code SCHEDULED} ocupa slot de disponibilidad (A-14): el predicado de la constraint
 * EXCLUDE de V006 y el del servicio de disponibilidad son el mismo.
 */
public enum AppointmentStatus {

    /** Reserva vigente: ocupa slot y bloquea la disponibilidad (A-14). */
    SCHEDULED,

    COMPLETED,

    CANCELLED,

    NO_SHOW
}
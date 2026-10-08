package com.dentalclinic.appointment.domain;

/**
 * Tipo de comunicación de la outbox (canal EMAIL). Conjunto cerrado: es el mismo que el CHECK de
 * {@code communication.type} y de {@code communication_intention.event_type}.
 *
 * <p>Las notificaciones in-app de completados/no-show/arrive/start NO generan comunicaciones: solo
 * {@code CREATED}, {@code RESCHEDULED}, {@code CANCELLED} y (en F8) {@code REMINDER} tienen fila.
 */
public enum CommunicationType {

    CREATED,
    RESCHEDULED,
    CANCELLED,
    REMINDER
}
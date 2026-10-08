package com.dentalclinic.appointment.domain;

/**
 * Prioridad de una notificación in-app. Fase 6 usa {@code NORMAL} en todas las inserciones; la
 * columna es un CHECK cerrado ({@code NORMAL|HIGH}) para consumidores posteriores.
 */
public enum NotificationPriority {

    NORMAL,
    HIGH
}
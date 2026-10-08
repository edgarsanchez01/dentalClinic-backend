package com.dentalclinic.appointment.domain;

/**
 * Estado de entrega de una comunicación EMAIL. Fase 6 solo lo crea con {@code PENDING}; el
 * dispatcher (Fase 8) es el único que lo avanza a {@code SENT}/{@code FAILED} (D-F6-01/C.2).
 */
public enum CommunicationStatus {

    PENDING,
    SENT,
    FAILED
}
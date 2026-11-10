package com.dentalclinic.clinical.domain;

/**
 * Estados de un tratamiento clinico (RF-TR-06, PRD 20.1).
 *
 * <p>Conjunto cerrado de cuatro valores. No hay estados auxiliares ni transiciones
 * inventadas: la gestion del estado consiste en fijar uno de estos valores.
 */
public enum TreatmentStatus {
    PLANNED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}

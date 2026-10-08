package com.dentalclinic.appointment.domain;

/**
 * Conjunto cerrado de eventos de una cita (PRD.md §14.6, D-F6-02/A-05).
 *
 * <p>Fase 6 emite únicamente los relacionados con el ciclo de vida; {@code REMINDER_SENT},
 * {@code REMINDER_FAILED} y {@code FEEDBACK_REQUESTED} los emite F8 (Frontera F6↔F8). El CHECK de
 * la base de datos declara el conjunto completo para que el esquema sea fiel al documento y ningún
 * evento futuro quede fuera.
 */
public enum AppointmentEventType {

    APPOINTMENT_CREATED,
    APPOINTMENT_RESCHEDULED,
    APPOINTMENT_CANCELLED,
    APPOINTMENT_COMPLETED,
    APPOINTMENT_NO_SHOW,
    PATIENT_ARRIVED,
    APPOINTMENT_STARTED,
    REMINDER_SENT,
    REMINDER_FAILED,
    FEEDBACK_REQUESTED
}
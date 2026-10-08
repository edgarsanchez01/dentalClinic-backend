package com.dentalclinic.appointment.domain;

/**
 * Identidad del destinatario de una comunicación o intención (A-06).
 *
 * <p>{@code PATIENT} usa la clave gramatical {@code {patientId}} (paciente sin cuenta); {@code USER}
 * usa {@code U{users.id}} (destinatario con cuenta). La deduplicación de la outbox es POR IDENTIDAD
 * (A-04/D-F6-13), no por dirección.
 */
public enum RecipientKind {

    PATIENT,
    USER
}
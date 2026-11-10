package com.dentalclinic.appointment.application;

import java.time.Instant;

import com.dentalclinic.appointment.domain.CommunicationType;

/**
 * Vista inmutable de una comunicación reclamada por el dispatcher. Se forma dentro de la breve
 * transacción que reserva la fila; el envío SMTP ocurre después, con esta copia ya separada de la
 * entidad y de su transacción (nunca se mantiene una transacción abierta durante SMTP).
 * Para REMINDER, leaseUntil + attempts identifican la reserva; reclamar no suma un intento.
 * La preparación final devuelve otra vista con el contador incrementado antes de SMTP.
 */
public record ClaimedEmail(Long id, String address, CommunicationType type, int occurrenceVersion,
        int attempts, Long appointmentId, Instant leaseUntil) {
    @Override
    public String toString() {
        return "ClaimedEmail{id=" + id + ", type=" + type + ", occurrenceVersion=" + occurrenceVersion
                + ", attempts=" + attempts + "}";
    }
}

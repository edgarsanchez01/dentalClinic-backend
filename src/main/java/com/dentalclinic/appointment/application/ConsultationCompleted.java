package com.dentalclinic.appointment.application;

import java.time.Instant;

/** Evento síncrono dentro de la transacción de cierre; no lleva datos clínicos. */
public record ConsultationCompleted(Long appointmentId, Long patientId, int occurrenceVersion,
        Long actorUserId, Instant completedAt) { }

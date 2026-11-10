package com.dentalclinic.odontogram.domain;

import java.time.Instant;
import java.util.Objects;
import com.dentalclinic.appointment.domain.RecordedAppointmentContext;
import jakarta.persistence.*;

/** Immutable common attribution; no relation to the assigned dentist is inferred. */
@MappedSuperclass
public abstract class OdontogramChange {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "actor_user_id", nullable = false, updatable = false) private Long actorUserId;
    @Column(name = "changed_at", nullable = false, updatable = false) private Instant changedAt;
    @Embedded private RecordedAppointmentContext appointmentContext;

    protected void attribute(Long actor, Instant at, RecordedAppointmentContext context) {
        actorUserId = Objects.requireNonNull(actor);
        changedAt = Objects.requireNonNull(at);
        appointmentContext = context;
    }
    public Long getId() { return id; }
    public Long getActorUserId() { return actorUserId; }
    public Instant getChangedAt() { return changedAt; }
    public RecordedAppointmentContext getAppointmentContext() { return appointmentContext; }
}

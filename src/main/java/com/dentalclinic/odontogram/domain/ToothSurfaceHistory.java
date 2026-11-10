package com.dentalclinic.odontogram.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Embedded;
import com.dentalclinic.appointment.domain.RecordedAppointmentContext;
import org.hibernate.annotations.Immutable;

/**
 * Cambio de condición de una superficie, con quién y cuándo (RF-OD-04, F7.4.3.2).
 *
 * <p><strong>Append-only:</strong> no se edita ni se elimina; la entidad no expone mutadores. El
 * autor es el {@code User} OWNER/DENTIST autenticado (RF-OD-08) y se
 * referencia por {@code actor_user_id}. {@code previous_condition} es nulo en el alta.
 *
 * <p>Es la contrapartida de {@link ToothHistory} para una {@link ToothSurfaceRecord}. Se modela en
 * tabla separada (V011) en vez de extender {@code tooth_history} para que V011 sea puramente
 * aditiva y no altere restricciones existentes de V010.
 */
@Entity
@Immutable
@Table(name = "tooth_surface_history")
public class ToothSurfaceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tooth_surface_record_id", nullable = false, updatable = false)
    private Long toothSurfaceRecordId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_condition", length = 12)
    private ToothCondition previousCondition;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_condition", nullable = false, length = 12)
    private ToothCondition newCondition;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private Long actorUserId;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    @Embedded private RecordedAppointmentContext appointmentContext;

    protected ToothSurfaceHistory() {
        // Para JPA.
    }

    /** Registra una transición de condición ({@code previousCondition} nulo en el alta). */
    public static ToothSurfaceHistory create(Long toothSurfaceRecordId, ToothCondition previousCondition,
                                             ToothCondition newCondition, Long actorUserId, Instant changedAt) {
        return create(toothSurfaceRecordId, previousCondition, newCondition, actorUserId, changedAt, null);
    }

    public static ToothSurfaceHistory create(Long toothSurfaceRecordId, ToothCondition previousCondition,
            ToothCondition newCondition, Long actorUserId, Instant changedAt, RecordedAppointmentContext context) {
        ToothSurfaceHistory history = new ToothSurfaceHistory();
        history.toothSurfaceRecordId = Objects.requireNonNull(toothSurfaceRecordId,
                "toothSurfaceRecordId es obligatorio");
        history.previousCondition = previousCondition;
        history.newCondition = Objects.requireNonNull(newCondition, "newCondition es obligatorio");
        history.actorUserId = Objects.requireNonNull(actorUserId, "actorUserId es obligatorio");
        history.changedAt = Objects.requireNonNull(changedAt, "changedAt es obligatorio");
        history.appointmentContext = context;
        return history;
    }

    public Long getId() {
        return id;
    }

    public Long getToothSurfaceRecordId() {
        return toothSurfaceRecordId;
    }

    public ToothCondition getPreviousCondition() {
        return previousCondition;
    }

    public ToothCondition getNewCondition() {
        return newCondition;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
    public RecordedAppointmentContext getAppointmentContext() { return appointmentContext; }
}

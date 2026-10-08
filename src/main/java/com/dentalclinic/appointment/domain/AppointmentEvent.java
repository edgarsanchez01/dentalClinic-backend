package com.dentalclinic.appointment.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Auditoría append-only de una cita (RF-AP-05/15, Architecture.md §15.2).
 *
 * <p>No es estado: la cita vive en {@code Appointment}. La existencia de cada evento se decide en la
 * capa de aplicación (agendado, llegada, comienzo, reprogramado, etc.); solo {@code PATIENT_ARRIVED}
 * y {@code APPOINTMENT_STARTED} están acotados a "uno por cita" también por índice parcial UNIQUE
 * (A-09/D-F6-24.b) para que duplicarlos sea, además, 409 por violación de constraint.
 */
@Entity
@Table(name = "appointment_event")
public class AppointmentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "appointment_id", nullable = false)
    private Long appointmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private AppointmentEventType eventType;

    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;

    @Column(name = "occurrence_version", nullable = false)
    private int occurrenceVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT now()")
    private Instant createdAt;

    protected AppointmentEvent() {
        // Para JPA.
    }

    private AppointmentEvent(Long appointmentId, AppointmentEventType eventType, Long actorUserId,
                             int occurrenceVersion, Map<String, Object> metadata) {
        this.appointmentId = Objects.requireNonNull(appointmentId, "appointmentId es obligatorio");
        this.eventType = Objects.requireNonNull(eventType, "eventType es obligatorio");
        this.actorUserId = Objects.requireNonNull(actorUserId, "actorUserId es obligatorio");
        this.occurrenceVersion = occurrenceVersion;
        this.metadata = metadata;
    }

    public static AppointmentEvent create(Long appointmentId, AppointmentEventType eventType,
                                          Long actorUserId, int occurrenceVersion,
                                          Map<String, Object> metadata) {
        return new AppointmentEvent(appointmentId, eventType, actorUserId, occurrenceVersion,
                metadata);
    }

    public Long getId() {
        return id;
    }

    public Long getAppointmentId() {
        return appointmentId;
    }

    public AppointmentEventType getEventType() {
        return eventType;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public int getOccurrenceVersion() {
        return occurrenceVersion;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
package com.dentalclinic.appointment.domain;

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

/**
 * Cita (RF-AP-01..18, Architecture.md §15.2).
 *
 * <p>{@code createdByUserId} (actor) y {@code dentistId} (profesional asignado) son conceptos
 * distintos y columnas distintas (PRD.md §11.1). {@code occurrenceVersion} comienza en 1 y solo la
 * reprogramación lo incrementa (RF-AP-18); la clave del recordatorio lo incluye (A-04).
 *
 * <p>La disponibilidad usa el intervalo semiabierto {@code [start_at, end_at)} (RF-AV-06); la
 * constraint EXCLUDE de V006 impide que dos citas {@code SCHEDULED} del mismo dentista solapen
 * (A-14). {@code durationMinutes} es derivado de {@code end - start} en escritura.
 */
@Entity
@Table(name = "appointment")
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "dentist_id", nullable = false)
    private Long dentistId;

    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private AppointmentStatus status;

    @Column(name = "occurrence_version", nullable = false)
    private int occurrenceVersion;

    @Column(name = "reason")
    private String reason;

    @Column(name = "notes")
    private String notes;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT now()")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Appointment() {
        // Para JPA.
    }

    private Appointment(Long patientId, Long dentistId, Long createdByUserId, Instant startAt,
                        Instant endAt, int durationMinutes, String reason, String notes) {
        this.patientId = Objects.requireNonNull(patientId, "patientId es obligatorio");
        this.dentistId = Objects.requireNonNull(dentistId, "dentistId es obligatorio");
        this.createdByUserId = Objects.requireNonNull(createdByUserId, "createdByUserId es obligatorio");
        this.startAt = Objects.requireNonNull(startAt, "startAt es obligatorio");
        this.endAt = Objects.requireNonNull(endAt, "endAt es obligatorio");
        this.durationMinutes = durationMinutes;
        this.reason = reason;
        this.notes = notes;
        this.status = AppointmentStatus.SCHEDULED;
        this.occurrenceVersion = 1;
    }

    /** Nace {@code SCHEDULED} con {@code occurrenceVersion = 1} (RF-AP-18). */
    public static Appointment create(Long patientId, Long dentistId, Long createdByUserId,
                                     Instant startAt, Instant endAt, int durationMinutes,
                                     String reason, String notes) {
        return new Appointment(patientId, dentistId, createdByUserId, startAt, endAt,
                durationMinutes, reason, notes);
    }

    /** Sigue estando {@code SCHEDULED} (la reprogramación no cambia de estado) e incrementa la versión. */
    public void reschedule(Instant startAt, Instant endAt, int durationMinutes, Instant now) {
        this.startAt = Objects.requireNonNull(startAt, "startAt es obligatorio");
        this.endAt = Objects.requireNonNull(endAt, "endAt es obligatorio");
        this.durationMinutes = durationMinutes;
        this.occurrenceVersion = this.occurrenceVersion + 1;
        this.updatedAt = now;
    }

    public void cancel(Instant now) {
        this.status = AppointmentStatus.CANCELLED;
        this.updatedAt = now;
    }

    public void complete(Instant now) {
        this.status = AppointmentStatus.COMPLETED;
        this.completedAt = now;
        this.updatedAt = now;
    }

    public void markNoShow(Instant now) {
        this.status = AppointmentStatus.NO_SHOW;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getPatientId() {
        return patientId;
    }

    public Long getDentistId() {
        return dentistId;
    }

    public Long getCreatedByUserId() {
        return createdByUserId;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public AppointmentStatus getStatus() {
        return status;
    }

    public int getOccurrenceVersion() {
        return occurrenceVersion;
    }

    public String getReason() {
        return reason;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
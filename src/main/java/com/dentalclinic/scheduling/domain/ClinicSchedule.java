package com.dentalclinic.scheduling.domain;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Objects;

import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.descriptor.jdbc.LocalTimeJdbcType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Franja de apertura semanal de la clínica (RF-SC-01).
 *
 * <p>Una fila por día abierto; la <strong>ausencia de fila significa "cerrado"</strong>. El día es
 * ISO-8601 (1 = lunes .. 7 = domingo). Las horas son locales (wall-clock) a {@code Clinic.timezone}.
 */
@Entity
@Table(name = "clinic_schedule")
public class ClinicSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id", nullable = false)
    private Long clinicId;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "day_of_week", nullable = false)
    private int dayOfWeek;

    @JdbcType(LocalTimeJdbcType.class)
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @JdbcType(LocalTimeJdbcType.class)
    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT now()")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected ClinicSchedule() {
        // Para JPA.
    }

    private ClinicSchedule(Long clinicId, int dayOfWeek, LocalTime startTime, LocalTime endTime) {
        this.clinicId = Objects.requireNonNull(clinicId, "clinicId es obligatorio");
        this.dayOfWeek = dayOfWeek;
        this.startTime = Objects.requireNonNull(startTime, "startTime es obligatorio");
        this.endTime = Objects.requireNonNull(endTime, "endTime es obligatorio");
    }

    public static ClinicSchedule create(Long clinicId, int dayOfWeek, LocalTime startTime,
                                        LocalTime endTime) {
        return new ClinicSchedule(clinicId, dayOfWeek, startTime, endTime);
    }

    public Long getId() {
        return id;
    }

    public Long getClinicId() {
        return clinicId;
    }

    public int getDayOfWeek() {
        return dayOfWeek;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
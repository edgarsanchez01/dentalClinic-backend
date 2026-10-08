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
 * Franja de atención semanal de un dentista (RF-SC-03).
 *
 * <p>Una fila por día en que atiende; la ausencia de fila significa "no atiende". Debe quedar
 * contenida en la ventana efectiva de la clínica (validado transaccionalmente en aplicación).
 */
@Entity
@Table(name = "dentist_schedule")
public class DentistSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dentist_id", nullable = false)
    private Long dentistId;

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

    protected DentistSchedule() {
        // Para JPA.
    }

    private DentistSchedule(Long dentistId, int dayOfWeek, LocalTime startTime, LocalTime endTime) {
        this.dentistId = Objects.requireNonNull(dentistId, "dentistId es obligatorio");
        this.dayOfWeek = dayOfWeek;
        this.startTime = Objects.requireNonNull(startTime, "startTime es obligatorio");
        this.endTime = Objects.requireNonNull(endTime, "endTime es obligatorio");
    }

    public static DentistSchedule create(Long dentistId, int dayOfWeek, LocalTime startTime,
                                         LocalTime endTime) {
        return new DentistSchedule(dentistId, dayOfWeek, startTime, endTime);
    }

    public Long getId() {
        return id;
    }

    public Long getDentistId() {
        return dentistId;
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
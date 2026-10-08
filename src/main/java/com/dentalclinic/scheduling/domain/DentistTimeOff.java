package com.dentalclinic.scheduling.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

import org.hibernate.annotations.JdbcType;
import org.hibernate.type.descriptor.jdbc.LocalTimeJdbcType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Franquicia de un dentista (RF-SC-06).
 *
 * <p>Opcion A de Fase 5: con {@code startTime}/{@code endTime} nulos la ausencia cubre días
 * completos de {@code startDate} a {@code endDate} (inclusive); con ambas horas presentes es una
 * franja parcial de <strong>un solo día</strong> ({@code startDate == endDate}).
 */
@Entity
@Table(name = "dentist_time_off")
public class DentistTimeOff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dentist_id", nullable = false)
    private Long dentistId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @JdbcType(LocalTimeJdbcType.class)
    @Column(name = "start_time")
    private LocalTime startTime;

    @JdbcType(LocalTimeJdbcType.class)
    @Column(name = "end_time")
    private LocalTime endTime;

    @Column(name = "reason")
    private String reason;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT now()")
    private Instant createdAt;

    protected DentistTimeOff() {
        // Para JPA.
    }

    private DentistTimeOff(Long dentistId, LocalDate startDate, LocalDate endDate, LocalTime startTime,
                           LocalTime endTime, String reason) {
        this.dentistId = Objects.requireNonNull(dentistId, "dentistId es obligatorio");
        this.startDate = Objects.requireNonNull(startDate, "startDate es obligatorio");
        this.endDate = Objects.requireNonNull(endDate, "endDate es obligatorio");
        this.startTime = startTime;
        this.endTime = endTime;
        this.reason = reason;
    }

    public static DentistTimeOff create(Long dentistId, LocalDate startDate, LocalDate endDate,
                                        LocalTime startTime, LocalTime endTime, String reason) {
        return new DentistTimeOff(dentistId, startDate, endDate, startTime, endTime, reason);
    }

    /** Reemplaza la franquicia (PUT idempotente por id). */
    public void update(LocalDate startDate, LocalDate endDate, LocalTime startTime, LocalTime endTime,
                       String reason) {
        this.startDate = Objects.requireNonNull(startDate, "startDate es obligatorio");
        this.endDate = Objects.requireNonNull(endDate, "endDate es obligatorio");
        this.startTime = startTime;
        this.endTime = endTime;
        this.reason = reason;
    }

    public boolean isAllDay() {
        return startTime == null && endTime == null;
    }

    public Long getId() {
        return id;
    }

    public Long getDentistId() {
        return dentistId;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
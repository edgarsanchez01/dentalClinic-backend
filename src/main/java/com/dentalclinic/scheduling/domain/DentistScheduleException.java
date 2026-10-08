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
 * Excepción por fecha del horario de un dentista (RF-SC-07).
 *
 * <p>Sustituye su ventana semanal solo para esa fecha. No puede abrir cuando la clínica está
 * cerrada ni exceder la ventana especial de la clínica (validado en aplicación → 409).
 */
@Entity
@Table(name = "dentist_schedule_exception")
public class DentistScheduleException {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dentist_id", nullable = false)
    private Long dentistId;

    @Column(name = "exception_date", nullable = false)
    private LocalDate exceptionDate;

    @Column(name = "closed", nullable = false)
    private boolean closed;

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

    protected DentistScheduleException() {
        // Para JPA.
    }

    private DentistScheduleException(Long dentistId, LocalDate exceptionDate, boolean closed,
                                     LocalTime startTime, LocalTime endTime, String reason) {
        this.dentistId = Objects.requireNonNull(dentistId, "dentistId es obligatorio");
        this.exceptionDate = Objects.requireNonNull(exceptionDate, "exceptionDate es obligatorio");
        this.closed = closed;
        this.startTime = startTime;
        this.endTime = endTime;
        this.reason = reason;
    }

    public static DentistScheduleException create(Long dentistId, LocalDate exceptionDate, boolean closed,
                                                  LocalTime startTime, LocalTime endTime, String reason) {
        return new DentistScheduleException(dentistId, exceptionDate, closed, startTime, endTime, reason);
    }

    public Long getId() {
        return id;
    }

    public Long getDentistId() {
        return dentistId;
    }

    public LocalDate getExceptionDate() {
        return exceptionDate;
    }

    public boolean isClosed() {
        return closed;
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
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
 * Excepción por fecha del horario de la clínica (RF-SC-05).
 *
 * <p>Sustituye la ventana semanal <strong>solo</strong> para esa fecha. {@code closed = true}
 * cierra el día completo y exige horas nulas; {@code closed = false} abre una ventana especial.
 * Los breaks semanales siguen aplicándose sobre la ventana efectiva (decisión F5).
 */
@Entity
@Table(name = "clinic_schedule_exception")
public class ClinicScheduleException {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinic_id", nullable = false)
    private Long clinicId;

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

    protected ClinicScheduleException() {
        // Para JPA.
    }

    private ClinicScheduleException(Long clinicId, LocalDate exceptionDate, boolean closed,
                                    LocalTime startTime, LocalTime endTime, String reason) {
        this.clinicId = Objects.requireNonNull(clinicId, "clinicId es obligatorio");
        this.exceptionDate = Objects.requireNonNull(exceptionDate, "exceptionDate es obligatorio");
        this.closed = closed;
        this.startTime = startTime;
        this.endTime = endTime;
        this.reason = reason;
    }

    public static ClinicScheduleException create(Long clinicId, LocalDate exceptionDate, boolean closed,
                                                 LocalTime startTime, LocalTime endTime, String reason) {
        return new ClinicScheduleException(clinicId, exceptionDate, closed, startTime, endTime, reason);
    }

    public Long getId() {
        return id;
    }

    public Long getClinicId() {
        return clinicId;
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
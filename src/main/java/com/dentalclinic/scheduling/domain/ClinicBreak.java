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
 * Break recurrente del horario semanal de la clínica (RF-SC-02).
 *
 * <p>Se resta de la ventana efectiva de cada día. El solapamiento entre breaks se impide de forma
 * concurrente con un {@code EXCLUDE USING gist} en la base; el rango es semiabierto
 * {@code [inicio, fin)}.
 */
@Entity
@Table(name = "clinic_break")
public class ClinicBreak {

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

    protected ClinicBreak() {
        // Para JPA.
    }

    private ClinicBreak(Long clinicId, int dayOfWeek, LocalTime startTime, LocalTime endTime) {
        this.clinicId = Objects.requireNonNull(clinicId, "clinicId es obligatorio");
        this.dayOfWeek = dayOfWeek;
        this.startTime = Objects.requireNonNull(startTime, "startTime es obligatorio");
        this.endTime = Objects.requireNonNull(endTime, "endTime es obligatorio");
    }

    public static ClinicBreak create(Long clinicId, int dayOfWeek, LocalTime startTime,
                                     LocalTime endTime) {
        return new ClinicBreak(clinicId, dayOfWeek, startTime, endTime);
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
}
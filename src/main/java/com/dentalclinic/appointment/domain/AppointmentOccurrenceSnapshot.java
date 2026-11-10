package com.dentalclinic.appointment.domain;

import java.time.Instant;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

/** Frozen scheduling context, never an encounter or evidence of attendance. */
@Entity
@Immutable
@IdClass(OccurrenceKey.class)
@Table(name = "appointment_occurrence_snapshot")
public class AppointmentOccurrenceSnapshot {
    @Id @Column(name = "appointment_id", updatable = false) private Long appointmentId;
    @Id @Column(name = "occurrence_version", updatable = false) private Integer occurrenceVersion;
    @Column(name = "patient_id", nullable = false, updatable = false) private Long patientId;
    @Column(name = "dentist_id", nullable = false, updatable = false) private Long dentistId;
    @Column(name = "scheduled_start_at", nullable = false, updatable = false) private Instant scheduledStartAt;
    @Column(name = "scheduled_end_at", nullable = false, updatable = false) private Instant scheduledEndAt;
    @Column(name = "timezone", nullable = false, length = 100, updatable = false) private String timezone;
    @Column(name = "captured_at", nullable = false, updatable = false) private Instant capturedAt;

    protected AppointmentOccurrenceSnapshot() { }

    public static AppointmentOccurrenceSnapshot capture(Appointment appointment, String timezone, Instant now) {
        var snapshot = new AppointmentOccurrenceSnapshot();
        snapshot.appointmentId = appointment.getId();
        snapshot.occurrenceVersion = appointment.getOccurrenceVersion();
        snapshot.patientId = appointment.getPatientId();
        snapshot.dentistId = appointment.getDentistId();
        snapshot.scheduledStartAt = appointment.getStartAt();
        snapshot.scheduledEndAt = appointment.getEndAt();
        snapshot.timezone = timezone;
        snapshot.capturedAt = now;
        return snapshot;
    }

    public Long getAppointmentId() { return appointmentId; }
    public Integer getOccurrenceVersion() { return occurrenceVersion; }
    public Long getPatientId() { return patientId; }
    public Long getDentistId() { return dentistId; }
    public Instant getScheduledStartAt() { return scheduledStartAt; }
    public Instant getScheduledEndAt() { return scheduledEndAt; }
    public String getTimezone() { return timezone; }
    public Instant getCapturedAt() { return capturedAt; }
}

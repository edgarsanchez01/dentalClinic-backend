package com.dentalclinic.appointment.domain;

import jakarta.persistence.*;

/** Immutable value stored on a clinical entry; null embedded value means general record. */
@Embeddable
public class RecordedAppointmentContext {
    @Column(name = "appointment_id", updatable = false) private Long appointmentId;
    @Column(name = "appointment_occurrence_version", updatable = false) private Integer occurrenceVersion;
    @Enumerated(EnumType.STRING)
    @Column(name = "appointment_status_at_registration", length = 12, updatable = false)
    private AppointmentStatus statusAtRegistration;

    protected RecordedAppointmentContext() { }

    public RecordedAppointmentContext(Long appointmentId, Integer occurrenceVersion, AppointmentStatus status) {
        this.appointmentId = appointmentId;
        this.occurrenceVersion = occurrenceVersion;
        this.statusAtRegistration = status;
    }

    public Long getAppointmentId() { return appointmentId; }
    public Integer getOccurrenceVersion() { return occurrenceVersion; }
    public AppointmentStatus getStatusAtRegistration() { return statusAtRegistration; }
}

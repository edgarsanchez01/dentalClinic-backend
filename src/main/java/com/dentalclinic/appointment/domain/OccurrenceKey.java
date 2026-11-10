package com.dentalclinic.appointment.domain;

import java.io.Serializable;
import java.util.Objects;

public class OccurrenceKey implements Serializable {
    private Long appointmentId;
    private Integer occurrenceVersion;

    public OccurrenceKey() { }

    public OccurrenceKey(Long appointmentId, Integer occurrenceVersion) {
        this.appointmentId = appointmentId;
        this.occurrenceVersion = occurrenceVersion;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OccurrenceKey key && Objects.equals(appointmentId, key.appointmentId)
                && Objects.equals(occurrenceVersion, key.occurrenceVersion);
    }

    @Override
    public int hashCode() { return Objects.hash(appointmentId, occurrenceVersion); }
}

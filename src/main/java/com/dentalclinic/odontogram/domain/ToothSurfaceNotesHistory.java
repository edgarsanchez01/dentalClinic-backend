package com.dentalclinic.odontogram.domain;

import java.time.Instant;
import java.util.Objects;
import com.dentalclinic.appointment.domain.RecordedAppointmentContext;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

@Entity @Immutable @Table(name = "tooth_surface_notes_history")
public class ToothSurfaceNotesHistory extends NotesHistoryChange {
    @Column(name = "tooth_surface_record_id", nullable = false, updatable = false) private Long toothSurfaceRecordId;
    protected ToothSurfaceNotesHistory() { }
    public static ToothSurfaceNotesHistory create(Long surface, String previous, String next, Long actor,
            Instant at, RecordedAppointmentContext context) {
        var event = new ToothSurfaceNotesHistory();
        event.toothSurfaceRecordId = Objects.requireNonNull(surface);
        event.change(previous, next);
        event.attribute(actor, at, context);
        return event;
    }
    public Long getToothSurfaceRecordId() { return toothSurfaceRecordId; }
}

package com.dentalclinic.odontogram.domain;

import java.time.Instant;
import java.util.Objects;
import com.dentalclinic.appointment.domain.RecordedAppointmentContext;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

@Entity @Immutable @Table(name = "tooth_notes_history")
public class ToothNotesHistory extends NotesHistoryChange {
    @Column(name = "tooth_record_id", nullable = false, updatable = false) private Long toothRecordId;
    protected ToothNotesHistory() { }
    public static ToothNotesHistory create(Long tooth, String previous, String next, Long actor,
            Instant at, RecordedAppointmentContext context) {
        var event = new ToothNotesHistory();
        event.toothRecordId = Objects.requireNonNull(tooth);
        event.change(previous, next);
        event.attribute(actor, at, context);
        return event;
    }
    public Long getToothRecordId() { return toothRecordId; }
}

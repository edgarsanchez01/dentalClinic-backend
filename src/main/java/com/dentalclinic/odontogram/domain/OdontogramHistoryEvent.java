package com.dentalclinic.odontogram.domain;

import java.time.Instant;
import java.util.Comparator;
import com.dentalclinic.appointment.domain.RecordedAppointmentContext;

/** Read value of one actual row; IDs are unique only together with source. */
public record OdontogramHistoryEvent(Long id, Long toothRecordId, Long surfaceRecordId, int toothFdi,
        ToothSurface surface, Source source, ToothCondition previousCondition, ToothCondition newCondition,
        String previousNotes, String newNotes, Long treatmentId, TreatmentLinkChange changeKind,
        Long actorUserId, Instant changedAt, RecordedAppointmentContext occurrence) {
    public enum Kind { CONDITION, NOTES, TREATMENT_LINK }
    public enum Source {
        CONDITION_TOOTH(1, Kind.CONDITION), NOTES_TOOTH(2, Kind.NOTES),
        TREATMENT_LINK(3, Kind.TREATMENT_LINK), CONDITION_SURFACE(4, Kind.CONDITION), NOTES_SURFACE(5, Kind.NOTES);
        private final int rank;
        private final Kind kind;
        Source(int rank, Kind kind) { this.rank = rank; this.kind = kind; }
        public int rank() { return rank; }
        public Kind kind() { return kind; }
    }
    public static final Comparator<OdontogramHistoryEvent> ORDER = Comparator
            .comparing(OdontogramHistoryEvent::changedAt)
            .thenComparingInt(event -> event.source().rank()).thenComparing(OdontogramHistoryEvent::id);
}

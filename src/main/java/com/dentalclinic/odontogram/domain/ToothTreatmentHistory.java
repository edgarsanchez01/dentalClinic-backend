package com.dentalclinic.odontogram.domain;

import java.time.Instant;
import java.util.Objects;
import com.dentalclinic.appointment.domain.RecordedAppointmentContext;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

@Entity @Immutable @Table(name = "tooth_treatment_history")
public class ToothTreatmentHistory extends OdontogramChange {
    @Column(name = "tooth_record_id", nullable = false, updatable = false) private Long toothRecordId;
    @Column(name = "treatment_id", nullable = false, updatable = false) private Long treatmentId;
    @Enumerated(EnumType.STRING) @Column(name = "change_kind", nullable = false, length = 8, updatable = false)
    private TreatmentLinkChange changeKind;
    protected ToothTreatmentHistory() { }
    public static ToothTreatmentHistory create(Long tooth, Long treatment, TreatmentLinkChange kind,
            Long actor, Instant at, RecordedAppointmentContext context) {
        var event = new ToothTreatmentHistory();
        event.toothRecordId = Objects.requireNonNull(tooth);
        event.treatmentId = Objects.requireNonNull(treatment);
        event.changeKind = Objects.requireNonNull(kind);
        event.attribute(actor, at, context);
        return event;
    }
    public Long getToothRecordId() { return toothRecordId; }
    public Long getTreatmentId() { return treatmentId; }
    public TreatmentLinkChange getChangeKind() { return changeKind; }
}

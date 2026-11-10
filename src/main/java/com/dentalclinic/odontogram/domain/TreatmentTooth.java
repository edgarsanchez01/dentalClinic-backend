package com.dentalclinic.odontogram.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Vínculo entre un tratamiento clínico y una pieza del odontograma (RF-OD-05).
 *
 * <p>Es la relación que conecta {@code treatment} (F7.4.1) con {@code tooth_record}, en lugar de
 * una clave foránea dentro del hallazgo, para que un tratamiento pueda tratar varias piezas y una
 * pieza pueda aparecer en varios tratamientos.
 */
@Entity
@Table(name = "treatment_tooth")
public class TreatmentTooth {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "treatment_id", nullable = false, updatable = false)
    private Long treatmentId;

    @Column(name = "tooth_record_id", nullable = false, updatable = false)
    private Long toothRecordId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TreatmentTooth() {
        // Para JPA.
    }

    public static TreatmentTooth link(Long treatmentId, Long toothRecordId, Instant now) {
        TreatmentTooth link = new TreatmentTooth();
        link.treatmentId = Objects.requireNonNull(treatmentId, "treatmentId es obligatorio");
        link.toothRecordId = Objects.requireNonNull(toothRecordId, "toothRecordId es obligatorio");
        link.createdAt = Objects.requireNonNull(now, "now es obligatorio");
        return link;
    }

    public Long getId() {
        return id;
    }

    public Long getTreatmentId() {
        return treatmentId;
    }

    public Long getToothRecordId() {
        return toothRecordId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

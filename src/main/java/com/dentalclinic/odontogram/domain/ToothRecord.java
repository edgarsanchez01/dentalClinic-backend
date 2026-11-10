package com.dentalclinic.odontogram.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Estado vigente de una pieza dental (FDI) en el expediente de un paciente (RF-OD-01/03).
 *
 * <p>Granularidad <strong>por pieza</strong>: cada paciente tiene como mucho un registro por
 * código FDI (F7.4.3.1). El estado por superficie ({@code ToothSurfaceRecord}, RF-OD-02) está
 * diferido a F7.4.3.2 y no se modela aquí.
 *
 * <p>Cada cambio de condición se registra en {@link ToothHistory} (append-only). La entidad solo
 * expone el estado actual; el historial lo orquesta el servicio de aplicación.
 */
@Entity
@Table(name = "tooth_record")
public class ToothRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "tooth_fdi", nullable = false)
    private int toothFdi;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition", nullable = false, length = 12)
    private ToothCondition condition;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected ToothRecord() {
        // Para JPA.
    }

    /**
     * Crea el registro vigente de una pieza.
     *
     * @throws InvalidToothException si el código FDI no es válido o la condición es nula
     */
    public static ToothRecord create(Long patientId, int toothFdi, ToothCondition condition,
                                     String notes, Instant now) {
        if (!ToothNotation.isValid(toothFdi)) {
            throw new InvalidToothException(
                    "La pieza dental " + toothFdi + " no es una notación FDI válida.");
        }
        ToothRecord record = new ToothRecord();
        record.patientId = Objects.requireNonNull(patientId, "patientId es obligatorio");
        record.toothFdi = toothFdi;
        record.createdAt = Objects.requireNonNull(now, "now es obligatorio");
        record.apply(condition, notes);
        return record;
    }

    /** Reemplaza la condición y las observaciones vigentes y sella la marca de actualización. */
    public void update(ToothCondition condition, String notes, Instant updatedAt) {
        apply(condition, notes);
        this.updatedAt = updatedAt;
    }

    private void apply(ToothCondition condition, String notes) {
        if (condition == null) {
            throw new InvalidToothException("La condición de la pieza es obligatoria.");
        }
        String normalizedNotes = normalizeText(notes);
        if (normalizedNotes != null && normalizedNotes.length() > 4000) {
            throw new InvalidToothException("Las observaciones admiten hasta 4000 caracteres.");
        }
        this.condition = condition;
        this.notes = normalizedNotes;
    }

    private static String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public Long getId() {
        return id;
    }

    public Long getPatientId() {
        return patientId;
    }

    public int getToothFdi() {
        return toothFdi;
    }

    public ToothCondition getCondition() {
        return condition;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

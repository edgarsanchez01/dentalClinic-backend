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
 * Estado vigente de una superficie de una pieza dental (RF-OD-02/03, F7.4.3.2).
 *
 * <p>Una superficie pertenece a un {@link ToothRecord}: la misma granularidad de "una sola fila
 * por (paciente, pieza)" se reproduce aquí con "una sola fila por (tooth_record, surface)". El
 * estado de la superficie complementa, no sustituye, el estado general de la pieza.
 *
 * <p>Cada cambio de condición se registra en {@link ToothSurfaceHistory} (append-only). La
 * entidad solo expone el estado actual; el historial lo orquesta el servicio de aplicación.
 *
 * <p>La regla de superficies por grupo (anterior/posterior según posición FDI) la valida el
 * dominio con {@link ToothSurface#allowedFor(int)}; la base de datos no puede expresarla con un
 * CHECK por requerir la pieza (V011 lo documenta).
 */
@Entity
@Table(name = "tooth_surface_record")
public class ToothSurfaceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tooth_record_id", nullable = false, updatable = false)
    private Long toothRecordId;

    @Enumerated(EnumType.STRING)
    @Column(name = "surface", nullable = false, length = 12, updatable = false)
    private ToothSurface surface;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition", nullable = false, length = 12)
    private ToothCondition condition;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected ToothSurfaceRecord() {
        // Para JPA.
    }

    /**
     * Crea el registro vigente de una superficie.
     *
     * @param toothFdi código FDI de la pieza a la que pertenece, para validar el grupo de la
     *                 superficie (no se persiste aquí: vive en {@link ToothRecord})
     * @throws InvalidToothException si el FDI no es válido, la superficie no existe en ese grupo
     *                               o la condición es nula
     */
    public static ToothSurfaceRecord create(Long toothRecordId, int toothFdi, ToothSurface surface,
                                            ToothCondition condition, String notes, Instant now) {
        if (!ToothNotation.isValid(toothFdi)) {
            throw new InvalidToothException(
                    "La pieza dental " + toothFdi + " no es una notación FDI válida.");
        }
        if (surface == null) {
            throw new InvalidToothException("La superficie es obligatoria.");
        }
        ToothSurfaceRecord record = new ToothSurfaceRecord();
        record.toothRecordId = Objects.requireNonNull(toothRecordId, "toothRecordId es obligatorio");
        record.surface = surface;
        if (!surface.isValidFor(toothFdi)) {
            throw new InvalidToothException(
                    "La superficie " + surface + " no es válida para la pieza " + toothFdi + ".");
        }
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
            throw new InvalidToothException("La condición de la superficie es obligatoria.");
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

    public Long getToothRecordId() {
        return toothRecordId;
    }

    public ToothSurface getSurface() {
        return surface;
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
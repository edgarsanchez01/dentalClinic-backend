package com.dentalclinic.clinical.domain;

import java.math.BigDecimal;
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
 * Tratamiento clinico de un paciente (RF-TR-04/05/06, PRD 20.1).
 *
 * <p>Es un concepto distinto del <strong>servicio previsto de una cita</strong> y del
 * <strong>catalogo administrativo</strong> ({@code service_catalog_item}): un tratamiento
 * describe el procedimiento/diagnostico clinico y su estado, no una reserva ni un cobro.
 *
 * <p>Se asocia a un {@code patient} y, de forma opcional, a una cita (RF-TR-05). El costo
 * historico ({@code price}) es nullable y sin moneda/IVA (P-09 OPEN): no genera deuda ni pago.
 *
 * <p>F7.4.1 no modela dientes/odontograma, evolucion historica, presupuestos ni multisesion.
 */
@Entity
@Table(name = "treatment")
public class Treatment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "appointment_id")
    private Long appointmentId;

    @Column(name = "description", nullable = false, length = 4000)
    private String description;

    @Column(name = "diagnosis", length = 4000)
    private String diagnosis;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private TreatmentStatus status;

    @Column(name = "notes")
    private String notes;

    @Column(name = "price", precision = 19, scale = 4)
    private BigDecimal price;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Treatment() {
        // Para JPA.
    }

    /**
     * Crea un tratamiento. Si no se indica estado, nace en {@link TreatmentStatus#PLANNED}.
     *
     * @param appointmentId referencia opcional a una cita; puede ser {@code null}
     */
    public static Treatment create(Long patientId, Long appointmentId, String description,
                                   String diagnosis, TreatmentStatus status, String notes,
                                   BigDecimal price, Instant now) {
        Treatment treatment = new Treatment();
        treatment.patientId = Objects.requireNonNull(patientId, "patientId es obligatorio");
        treatment.appointmentId = appointmentId;
        treatment.createdAt = Objects.requireNonNull(now, "now es obligatorio");
        treatment.applyDetails(description, diagnosis, status, notes, price);
        return treatment;
    }

    /** Reemplaza el contenido clinico editable y sella la marca de actualizacion. */
    public void update(String description, String diagnosis, TreatmentStatus status, String notes,
                       BigDecimal price, Instant updatedAt) {
        applyDetails(description, diagnosis, status, notes, price);
        this.updatedAt = updatedAt;
    }

    private void applyDetails(String description, String diagnosis, TreatmentStatus status,
                              String notes, BigDecimal price) {
        String normalizedDescription = description == null ? "" : description.strip();
        if (normalizedDescription.isEmpty() || normalizedDescription.length() > 4000) {
            throw new InvalidTreatmentException(
                    "La descripcion del tratamiento es obligatoria y admite hasta 4000 caracteres.");
        }
        String normalizedDiagnosis = normalizeText(diagnosis);
        if (normalizedDiagnosis != null && normalizedDiagnosis.length() > 4000) {
            throw new InvalidTreatmentException("El diagnostico admite hasta 4000 caracteres.");
        }
        if (price != null && (price.signum() < 0 || price.scale() > 4
                || price.precision() - price.scale() > 15)) {
            throw new InvalidTreatmentException(
                    "El precio debe ser no negativo, con hasta 15 enteros y 4 decimales.");
        }
        this.description = normalizedDescription;
        this.diagnosis = normalizedDiagnosis;
        this.status = status == null ? TreatmentStatus.PLANNED : status;
        this.notes = normalizeText(notes);
        this.price = price;
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

    public Long getAppointmentId() {
        return appointmentId;
    }

    public String getDescription() {
        return description;
    }

    public String getDiagnosis() {
        return diagnosis;
    }

    public TreatmentStatus getStatus() {
        return status;
    }

    public String getNotes() {
        return notes;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

package com.dentalclinic.clinical.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Embedded;
import com.dentalclinic.appointment.domain.RecordedAppointmentContext;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Nota de progreso clinico de un tratamiento (RF-TR-07, EVO-01, AD-26).
 *
 * <p>Es una entidad <strong>append-only</strong>: se agrega y se lista, pero no se edita ni se
 * elimina. La entidad no expone ningun mutador; una correccion se registra como una entrada nueva.
 * El autor es un {@code Dentist} (el {@code OWNER} siempre tiene perfil de dentista, AD-11) y se
 * referencia por {@code dentist.id}, no por la cuenta.
 *
 * <p>Es un concepto propio del modulo Treatments, ligado a un tratamiento concreto. <strong>No</strong>
 * se mezcla con {@link ClinicalNote} (notas del expediente) ni con el catalogo de servicios, ni
 * introduce sesiones/multisesion (no aprobadas).
 */
@Entity
@Table(name = "treatment_evolution")
public class TreatmentEvolution {

    private static final int MAX_CONTENT_LENGTH = 4000;
    private static final int MAX_REASON_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "treatment_id", nullable = false)
    private Long treatmentId;

    @Column(name = "author_dentist_id", nullable = false)
    private Long authorDentistId;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "corrects_evolution_id")
    private Long correctsEvolutionId;

    @Column(name = "correction_reason")
    private String correctionReason;

    @Embedded
    private RecordedAppointmentContext appointmentContext;

    protected TreatmentEvolution() {
        // Para JPA.
    }

    private TreatmentEvolution(Long treatmentId, Long authorDentistId, String content,
                               Instant createdAt) {
        this.treatmentId = Objects.requireNonNull(treatmentId, "treatmentId es obligatorio");
        this.authorDentistId = Objects.requireNonNull(authorDentistId,
                "authorDentistId es obligatorio");
        this.content = requiredContent(content);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt es obligatorio");
    }

    /** Registra una entrada nueva de evolucion para un tratamiento. */
    public static TreatmentEvolution write(Long treatmentId, Long authorDentistId, String content,
                                            Instant createdAt) {
        return new TreatmentEvolution(treatmentId, authorDentistId, content, createdAt);
    }

    public static TreatmentEvolution write(Long treatmentId, Long authorId, String content, Instant now,
                                           RecordedAppointmentContext context) {
        TreatmentEvolution entry = write(treatmentId, authorId, content, now);
        entry.appointmentContext = context;
        return entry;
    }

    /**
     * Registra una correccion de una entrada raiz: fila nueva que conserva tratamiento y contexto
     * historico del original, sustituye el contenido en su totalidad y conserva la evidencia
     * anterior. Un unico nivel: {@code root} debe ser una entrada raiz.
     */
    public static TreatmentEvolution correct(TreatmentEvolution root, Long authorDentistId, String content,
                                              String correctionReason, Instant createdAt) {
        Objects.requireNonNull(root, "root es obligatorio");
        TreatmentEvolution entry = new TreatmentEvolution(root.treatmentId, authorDentistId, content, createdAt);
        entry.correctsEvolutionId = Objects.requireNonNull(root.getId(), "La entrada original debe estar persistida");
        entry.correctionReason = requiredReason(correctionReason);
        entry.appointmentContext = root.appointmentContext;
        return entry;
    }

    private static String requiredReason(String value) {
        String trimmed = value == null ? null : value.strip();
        if (trimmed == null || trimmed.isEmpty() || trimmed.length() > MAX_REASON_LENGTH) {
            throw new InvalidTreatmentException(
                    "El motivo de la correccion es obligatorio y admite hasta 1000 caracteres.");
        }
        return trimmed;
    }

    public boolean isCorrection() { return correctsEvolutionId != null; }

    public Long getCorrectsEvolutionId() { return correctsEvolutionId; }

    public String getCorrectionReason() { return correctionReason; }

    public RecordedAppointmentContext getAppointmentContext() { return appointmentContext; }

    private static String requiredContent(String value) {
        String trimmed = value == null ? null : value.strip();
        if (trimmed == null || trimmed.isEmpty() || trimmed.length() > MAX_CONTENT_LENGTH) {
            throw new InvalidTreatmentException(
                    "La evolucion del tratamiento es obligatoria y admite hasta 4000 caracteres.");
        }
        return trimmed;
    }

    public Long getId() {
        return id;
    }

    public Long getTreatmentId() {
        return treatmentId;
    }

    public Long getAuthorDentistId() {
        return authorDentistId;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

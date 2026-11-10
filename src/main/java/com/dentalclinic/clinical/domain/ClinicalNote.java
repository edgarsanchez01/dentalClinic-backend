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
 * Nota clínica inmutable (RF-CX-04).
 *
 * <p>Solo se agregan; no se editan ni se eliminan: la entidad no expone ningún mutador. El autor
 * es el perfil {@code Dentist} del OWNER o DENTIST autenticado y se referencia por
 * {@code dentist.id}, no por la cuenta, porque el autor clínico es el profesional.
 */
@Entity
@Table(name = "clinical_note")
public class ClinicalNote {

    private static final int MAX_REASON_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinical_record_id", nullable = false)
    private Long clinicalRecordId;

    @Column(name = "author_dentist_id", nullable = false)
    private Long authorDentistId;

    @Column(name = "content", nullable = false)
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "corrects_note_id")
    private Long correctsNoteId;

    @Column(name = "correction_reason")
    private String correctionReason;

    @Embedded
    private RecordedAppointmentContext appointmentContext;

    protected ClinicalNote() {
        // Para JPA.
    }

    private ClinicalNote(Long clinicalRecordId, Long authorDentistId, String content, Instant createdAt) {
        this.clinicalRecordId = Objects.requireNonNull(clinicalRecordId, "clinicalRecordId es obligatorio");
        this.authorDentistId = Objects.requireNonNull(authorDentistId, "authorDentistId es obligatorio");
        this.content = Objects.requireNonNull(content, "content es obligatorio");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt es obligatorio");
    }

    public static ClinicalNote write(Long clinicalRecordId, Long authorDentistId, String content,
                                     Instant createdAt) {
        return new ClinicalNote(clinicalRecordId, authorDentistId, content, createdAt);
    }

    public static ClinicalNote write(Long recordId, Long authorId, String content, Instant now,
                                     RecordedAppointmentContext context) {
        ClinicalNote note = write(recordId, authorId, content, now);
        note.appointmentContext = context;
        return note;
    }

    /**
     * Registra una correccion de una nota raiz: fila nueva que conserva expediente y contexto
     * historico del original, sustituye el contenido en su totalidad y conserva la evidencia
     * anterior. Un unico nivel: {@code root} debe ser una nota raiz (sin {@code correctsNoteId}).
     */
    public static ClinicalNote correct(ClinicalNote root, Long authorDentistId, String content,
                                       String correctionReason, Instant createdAt) {
        Objects.requireNonNull(root, "root es obligatorio");
        ClinicalNote note = new ClinicalNote(root.clinicalRecordId, authorDentistId, content, createdAt);
        note.correctsNoteId = Objects.requireNonNull(root.getId(), "La nota original debe estar persistida");
        note.correctionReason = requiredReason(correctionReason);
        note.appointmentContext = root.appointmentContext;
        return note;
    }

    private static String requiredReason(String value) {
        String trimmed = value == null ? null : value.strip();
        if (trimmed == null || trimmed.isEmpty() || trimmed.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException(
                    "El motivo de la correccion es obligatorio y admite hasta 1000 caracteres.");
        }
        return trimmed;
    }

    public boolean isCorrection() { return correctsNoteId != null; }

    public Long getCorrectsNoteId() { return correctsNoteId; }

    public String getCorrectionReason() { return correctionReason; }

    public RecordedAppointmentContext getAppointmentContext() { return appointmentContext; }

    public Long getId() {
        return id;
    }

    public Long getClinicalRecordId() {
        return clinicalRecordId;
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

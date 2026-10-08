package com.dentalclinic.clinical.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Nota clínica inmutable (RF-CX-04).
 *
 * <p>Solo se agregan; no se editan ni se eliminan: la entidad no expone ningún mutador. El autor
 * es un {@code Dentist} —el único rol con escritura clínica— y se referencia por
 * {@code dentist.id}, no por la cuenta, porque el autor clínico es el profesional.
 */
@Entity
@Table(name = "clinical_note")
public class ClinicalNote {

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
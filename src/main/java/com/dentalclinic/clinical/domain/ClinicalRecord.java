package com.dentalclinic.clinical.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Expediente clínico de un paciente (RF-CX-01/02/06/07/08).
 *
 * <p>Es <strong>separado del perfil administrativo</strong> (Architecture.md §15.3): un paciente
 * puede tener datos administrativos sin expediente, y el expediente se crea de forma perezosa la
 * primera vez que un DENTIST lo guarda. Relación 1:1 con {@code Patient} garantizada por
 * {@code patient_id} único.
 *
 * <p>Los cuatro campos modelan el contrato mínimo de Fase 4. No incluyen consultas, diagnósticos
 * ni evolución (RF-CX-03) ni un historial cronológico (RF-CX-05): diferidos.
 */
@Entity
@Table(name = "clinical_record")
public class ClinicalRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_id", nullable = false, unique = true)
    private Long patientId;

    @Column(name = "medical_background")
    private String medicalBackground;

    @Column(name = "allergies")
    private String allergies;

    @Column(name = "clinical_history")
    private String clinicalHistory;

    @Column(name = "general_observations")
    private String generalObservations;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT now()")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected ClinicalRecord() {
        // Para JPA.
    }

    private ClinicalRecord(Long patientId) {
        this.patientId = patientId;
    }

    /** Expediente vacío para un paciente. Se rellena con {@link #update}. */
    public static ClinicalRecord createFor(Long patientId) {
        return new ClinicalRecord(patientId);
    }

    /** Reemplaza el contenido del expediente y sella la marca de actualización. */
    public void update(String medicalBackground, String allergies, String clinicalHistory,
                       String generalObservations, Instant updatedAt) {
        this.medicalBackground = medicalBackground;
        this.allergies = allergies;
        this.clinicalHistory = clinicalHistory;
        this.generalObservations = generalObservations;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getPatientId() {
        return patientId;
    }

    public String getMedicalBackground() {
        return medicalBackground;
    }

    public String getAllergies() {
        return allergies;
    }

    public String getClinicalHistory() {
        return clinicalHistory;
    }

    public String getGeneralObservations() {
        return generalObservations;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
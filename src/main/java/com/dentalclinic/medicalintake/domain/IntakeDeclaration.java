package com.dentalclinic.medicalintake.domain;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.dentalclinic.patient.domain.IntakePersonalData;
import com.dentalclinic.medicalintake.domain.IntakeAnswers.*;

@Entity @Immutable
@Table(name = "patient_intake_declaration", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"patient_id", "operation_id"}),
        @UniqueConstraint(columnNames = {"patient_id", "revision"})})
public class IntakeDeclaration {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "patient_id", nullable = false) private Long patientId;
    @Column(name = "clinical_record_id", nullable = false) private Long clinicalRecordId;
    @Column(name = "actor_user_id", nullable = false) private Long actorUserId;
    @Column(nullable = false) private int revision;
    @Column(name = "operation_id", nullable = false) private UUID operationId;
    @Column(name = "request_hash", nullable = false, length = 64) private String requestHash;
    @Column(name = "questionnaire_version", nullable = false, length = 80) private String questionnaireVersion;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private IntakePersonalData personal;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private Medical medical;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private Dental dental;
    @Column(name = "privacy_evidence_id", nullable = false) private Long privacyEvidenceId;
    @Column(name = "declaration_version", nullable = false, length = 80) private String declarationVersion;
    @Column(name = "declaration_text", nullable = false, columnDefinition = "text") private String declarationText;
    @Enumerated(EnumType.STRING) @Column(name = "confirmed_by", nullable = false, length = 20) private ConfirmedBy confirmedBy;
    @Column(name = "confirmed_at", nullable = false) private Instant confirmedAt;

    protected IntakeDeclaration() { }
    public IntakeDeclaration(Long patientId, Long recordId, Long actorId, int revision,
            UUID operationId, String requestHash, String questionnaireVersion,
            IntakePersonalData personal, Medical medical, Dental dental, Long privacyEvidenceId,
            String declarationVersion, String declarationText, ConfirmedBy confirmedBy, Instant now) {
        this.patientId = patientId;
        clinicalRecordId = recordId;
        actorUserId = actorId;
        this.revision = revision;
        this.operationId = operationId;
        this.requestHash = requestHash;
        this.questionnaireVersion = questionnaireVersion;
        this.personal = personal;
        this.medical = medical;
        this.dental = dental;
        this.privacyEvidenceId = privacyEvidenceId;
        this.declarationVersion = declarationVersion;
        this.declarationText = declarationText;
        this.confirmedBy = confirmedBy;
        confirmedAt = now;
    }
    public Long getId() { return id; }
    public Long getPatientId() { return patientId; }
    public Long getClinicalRecordId() { return clinicalRecordId; }
    public Long getActorUserId() { return actorUserId; }
    public int getRevision() { return revision; }
    public String getRequestHash() { return requestHash; }
    public String getQuestionnaireVersion() { return questionnaireVersion; }
    public IntakePersonalData getPersonal() { return personal; }
    public Medical getMedical() { return medical; }
    public Dental getDental() { return dental; }
    public Long getPrivacyEvidenceId() { return privacyEvidenceId; }
    public String getDeclarationVersion() { return declarationVersion; }
    public String getDeclarationText() { return declarationText; }
    public ConfirmedBy getConfirmedBy() { return confirmedBy; }
    public Instant getConfirmedAt() { return confirmedAt; }
}

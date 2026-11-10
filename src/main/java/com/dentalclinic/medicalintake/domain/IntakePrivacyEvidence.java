package com.dentalclinic.medicalintake.domain;

import java.time.Instant;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

@Entity @Immutable
@Table(name = "patient_intake_privacy", uniqueConstraints = @UniqueConstraint(columnNames = {"patient_id", "notice_version"}))
public class IntakePrivacyEvidence {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "patient_id", nullable = false) private Long patientId;
    @Column(name = "actor_user_id", nullable = false) private Long actorUserId;
    @Column(name = "notice_version", nullable = false, length = 80) private String noticeVersion;
    @Column(name = "notice_text", nullable = false, columnDefinition = "text") private String noticeText;
    @Column(name = "notice_hash", nullable = false, length = 64) private String noticeHash;
    @Column(name = "acknowledged", nullable = false) private boolean acknowledged;
    @Column(name = "processing_accepted", nullable = false) private boolean processingAccepted;
    @Column(name = "accepted_at", nullable = false) private Instant acceptedAt;

    protected IntakePrivacyEvidence() { }
    public IntakePrivacyEvidence(Long patientId, Long actorUserId, String version, String text, String hash, Instant now) {
        this.patientId = patientId;
        this.actorUserId = actorUserId;
        noticeVersion = version;
        noticeText = text;
        noticeHash = hash;
        acknowledged = true;
        processingAccepted = true;
        acceptedAt = now;
    }
    public Long getId() { return id; }
    public Long getActorUserId() { return actorUserId; }
    public String getNoticeVersion() { return noticeVersion; }
    public String getNoticeText() { return noticeText; }
    public String getNoticeHash() { return noticeHash; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public boolean isAcknowledged() { return acknowledged; }
    public boolean isProcessingAccepted() { return processingAccepted; }
}

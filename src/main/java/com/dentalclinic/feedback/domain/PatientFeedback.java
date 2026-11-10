package com.dentalclinic.feedback.domain;

import java.time.Instant;
import jakarta.persistence.*;

@Entity
@Table(name = "patient_feedback")
public class PatientFeedback {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "appointment_id", nullable = false, unique = true)
    private Long appointmentId;
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;
    @Column(nullable = false, length = 12)
    private String status;
    private Integer rating;
    @Column(length = 2000)
    private String comment;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "responded_at")
    private Instant respondedAt;

    protected PatientFeedback() { }

    public static PatientFeedback request(Long appointmentId, String hash, Instant now) {
        var feedback = new PatientFeedback();
        feedback.appointmentId = appointmentId;
        feedback.tokenHash = hash;
        feedback.status = "REQUESTED";
        feedback.createdAt = now;
        feedback.expiresAt = now.plus(java.time.Duration.ofDays(7));
        return feedback;
    }

    public String linkState(Instant now) {
        if ("RESPONDED".equals(status)) return "USED";
        return now.isBefore(expiresAt) ? "AVAILABLE" : "EXPIRED";
    }

    public void respond(int rating, String comment, Instant now) {
        if (!"AVAILABLE".equals(linkState(now))) throw new IllegalStateException("Enlace no disponible.");
        if (rating < 1 || rating > 5 || (comment != null && comment.length() > 2000)) {
            throw new IllegalArgumentException("Valoración no válida.");
        }
        this.rating = rating;
        this.comment = comment == null || comment.isBlank() ? null : comment.strip();
        this.respondedAt = now;
        this.status = "RESPONDED";
    }

    public Long getId() { return id; }
    public Long getAppointmentId() { return appointmentId; }
    public String getTokenHash() { return tokenHash; }
    public String getStatus() { return status; }
    public Integer getRating() { return rating; }
    public String getComment() { return comment; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRespondedAt() { return respondedAt; }
}

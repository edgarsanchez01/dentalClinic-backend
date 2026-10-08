package com.dentalclinic.appointment.domain;

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
 * Outbox transaccional + log de entrega del canal EMAIL (D-F6-01/A-02).
 *
 * <p>La fila se inserta en la MISMA transacción de negocio (F6) y la fase 8 la reclama. F6 crea la
 * fila {@code PENDING}, aplica {@code superseded_at} cuando una reprogramación anula versiones
 * anteriores (A-07/D-F6-20) y <strong>nunca</strong> cambia {@code status}: eso es del dispatcher
 * (Frontera F6↔F8).
 *
 * <p>Invariantes reflejadas en V006: {@code address} NOT NULL (imposible encolar sin dirección
 * real), {@code idempotency_key} UNIQUE por identidad (A-04/D-F6-13) y FK compuestas de forma
 * {@code recipient_kind ↔ ids} verificables por el motor (A-06).
 */
@Entity
@Table(name = "communication")
public class Communication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "appointment_id", nullable = false)
    private Long appointmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private CommunicationType type;

    @Column(name = "channel", nullable = false, length = 20)
    private String channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_kind", nullable = false, length = 10)
    private RecipientKind recipientKind;

    @Column(name = "recipient_patient_id")
    private Long recipientPatientId;

    @Column(name = "recipient_user_id")
    private Long recipientUserId;

    @Column(name = "address", nullable = false, length = 320)
    private String address;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "occurrence_version", nullable = false)
    private int occurrenceVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private CommunicationStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT now()")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Communication() {
        // Para JPA.
    }

    private Communication(Long appointmentId, CommunicationType type, RecipientKind recipientKind,
                          Long recipientPatientId, Long recipientUserId, String address,
                          String idempotencyKey, int occurrenceVersion) {
        this.appointmentId = Objects.requireNonNull(appointmentId, "appointmentId es obligatorio");
        this.type = Objects.requireNonNull(type, "type es obligatorio");
        this.channel = "EMAIL";
        this.recipientKind = Objects.requireNonNull(recipientKind, "recipientKind es obligatorio");
        this.recipientPatientId = recipientPatientId;
        this.recipientUserId = recipientUserId;
        this.address = Objects.requireNonNull(address, "address es obligatorio");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey es obligatorio");
        this.occurrenceVersion = occurrenceVersion;
        this.status = CommunicationStatus.PENDING;
        this.attempts = 0;
    }

    /** Fila encolable de EMAIL, lista para que el dispatcher la reclame (F8). */
    public static Communication create(Long appointmentId, CommunicationType type,
                                       RecipientKind recipientKind, Long recipientPatientId,
                                       Long recipientUserId, String address, String idempotencyKey,
                                       int occurrenceVersion) {
        return new Communication(appointmentId, type, recipientKind, recipientPatientId,
                recipientUserId, address, idempotencyKey, occurrenceVersion);
    }

    /** A-07/D-F6-20: marca de tratamiento para comunicaciones no duplicables anuladas. No es estado. */
    public void markSuperseded(Instant now) {
        this.supersededAt = Objects.requireNonNull(now, "now es obligatorio");
    }

    public Long getId() {
        return id;
    }

    public Long getAppointmentId() {
        return appointmentId;
    }

    public CommunicationType getType() {
        return type;
    }

    public String getChannel() {
        return channel;
    }

    public RecipientKind getRecipientKind() {
        return recipientKind;
    }

    public Long getRecipientPatientId() {
        return recipientPatientId;
    }

    public Long getRecipientUserId() {
        return recipientUserId;
    }

    public String getAddress() {
        return address;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public int getOccurrenceVersion() {
        return occurrenceVersion;
    }

    public CommunicationStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getSupersededAt() {
        return supersededAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
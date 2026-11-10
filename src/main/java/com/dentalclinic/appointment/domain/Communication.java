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
        Objects.requireNonNull(now, "now es obligatorio");
        if (this.supersededAt == null) {
            this.supersededAt = now;
        }
        if (type == CommunicationType.REMINDER) {
            this.nextRetryAt = null;
            this.updatedAt = now;
        }
    }

    /** Reserva de REMINDER: reclamar no equivale a intentar SMTP. El intento se suma al preparar. */
    public void reserveReminder(Instant leaseUntil, Instant now) {
        requireReminder();
        requireDispatchable();
        this.nextRetryAt = Objects.requireNonNull(leaseUntil);
        this.updatedAt = Objects.requireNonNull(now);
    }

    /** Una aceptación externa sigue siendo real aunque la cita se haya invalidado durante SMTP. */
    public void recordReminderAccepted(Instant now) {
        requireReminder();
        if (status == CommunicationStatus.SENT) {
            return;
        }
        this.status = CommunicationStatus.SENT;
        this.sentAt = Objects.requireNonNull(now);
        this.lastError = null;
        this.nextRetryAt = null;
        this.updatedAt = now;
        // supersededAt se conserva como historial; no reactiva la comunicación.
    }

    /** Fallo real de un envío en curso ya obsoleto: registrar error sin programar retry ni FAILED. */
    public void recordObsoleteReminderError(String error, Instant now) {
        requireReminder();
        if (status == CommunicationStatus.PENDING && supersededAt != null) {
            this.lastError = error;
            this.nextRetryAt = null;
            this.updatedAt = Objects.requireNonNull(now);
        }
    }

    private void requireReminder() {
        if (type != CommunicationType.REMINDER) {
            throw new IllegalStateException("Esta operación solo corresponde a recordatorios.");
        }
    }

    /**
     * El dispatcher (F8) reclama la fila antes de abrir SMTP: suma un intento y empuja
     * {@code nextRetryAt} hasta el fin del lease. El lease reserva la fila ante otros dispatchers
     * sin mantener una transacción abierta durante el envío; si el proceso muere, la fila se
     * vuelve elegible al vencer el lease (no se pierde).
     */
    public void markClaimStarted(Instant leaseUntil, Instant now) {
        requireDispatchable();
        this.attempts = this.attempts + 1;
        this.nextRetryAt = Objects.requireNonNull(leaseUntil, "leaseUntil es obligatorio");
        this.updatedAt = Objects.requireNonNull(now, "now es obligatorio");
    }

    /** SENT: el proveedor confirmó el envío (única transición terminal de éxito). */
    public void markSent(Instant now) {
        requireDispatchable();
        this.status = CommunicationStatus.SENT;
        this.sentAt = Objects.requireNonNull(now, "now es obligatorio");
        this.lastError = null;
        this.nextRetryAt = null;
        this.updatedAt = now;
    }

    /** Aceptación del intento reservado no-REMINDER, conservando una invalidación durante SMTP. */
    public void recordAccepted(Instant now) {
        if (type == CommunicationType.REMINDER || status != CommunicationStatus.PENDING) {
            throw new IllegalStateException("La aceptación no corresponde a un aviso pendiente.");
        }
        this.status = CommunicationStatus.SENT;
        this.sentAt = Objects.requireNonNull(now);
        this.lastError = null;
        this.nextRetryAt = null;
        this.updatedAt = now;
    }

    /** Fallo con reintentos disponibles: se conserva PENDING y se programa el siguiente intento. */
    public void scheduleRetry(String error, Instant nextRetryAt, Instant now) {
        requireDispatchable();
        this.lastError = error;
        this.nextRetryAt = Objects.requireNonNull(nextRetryAt, "nextRetryAt es obligatorio");
        this.updatedAt = Objects.requireNonNull(now, "now es obligatorio");
    }

    /** Fallo definitivo: se agotaron los intentos; queda registrada para intervención (R24/§34.12). */
    public void markFailed(String error, Instant now) {
        requireDispatchable();
        this.status = CommunicationStatus.FAILED;
        this.lastError = error;
        this.nextRetryAt = null;
        this.updatedAt = Objects.requireNonNull(now, "now es obligatorio");
    }

    private void requireDispatchable() {
        if (status != CommunicationStatus.PENDING || supersededAt != null) {
            throw new IllegalStateException(
                    "Solo una comunicacion PENDING vigente puede cambiar de estado de entrega");
        }
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

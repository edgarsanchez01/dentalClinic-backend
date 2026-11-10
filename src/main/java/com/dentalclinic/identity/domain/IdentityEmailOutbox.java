package com.dentalclinic.identity.domain;

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
 * Outbox transaccional de entrega de enlaces de identidad (F8B.2, D2).
 *
 * <p>Cada fila referencia una <strong>emisión</strong> de token: la cuenta destino, el propósito, el
 * destinatario, el nonce de reconstrucción y el hash que ya vive en {@code invitations} /
 * {@code password_reset_tokens}. El token en claro y su URL <strong>nunca</strong> se persisten: el
 * compositor recompone el token en el momento del envío a partir de {@code (purpose, user_id, nonce)}
 * y verifica que su SHA-256 coincide con {@link #getTokenHash()} antes de entregar (patrón §9.7).
 *
 * <p>Invariantes reflejadas en V018: {@code recipientEmail} NOT NULL (imposible encolar sin
 * dirección real), forma del nonce/hash por CHECK, UNIQUE de {@code idempotency_key} (idempotencia
 * de encolado), UNIQUE de {@code token_nonce}/{@code token_hash} (referencia de emisión inequívoca),
 * coherencia {@code (status = 'SENT') = (sent_at IS NOT NULL)} y un índice parcial UNIQUE que admite
 * una sola entrega vigente por {@code (user_id, purpose)}.
 *
 * <p>Estados y reserva de concurrencia:
 * <ul>
 *   <li>{@code PENDING → SENT} al confirmar el proveedor; {@code PENDING → FAILED} al agotar
 *       intentos; {@code PENDING → PENDING} con {@code superseded_at} cuando una emisión posterior
 *       invalida la entrega (marca, no estado: D-F6-20).</li>
 *   <li>El claim reserva con {@code lease_until}; {@code next_attempt_at} guarda el próximo intento
 *       tras un fallo con backoff. Si el proceso muere con el lease vivo, la fila vuelve a ser
 *       elegible al vencerlo (no se pierde).</li>
 * </ul>
 */
@Entity
@Table(name = "identity_email_outbox")
public class IdentityEmailOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 20, updatable = false)
    private IdentityEmailPurpose purpose;

    @Column(name = "recipient_email", nullable = false, length = 320, updatable = false)
    private String recipientEmail;

    @Column(name = "token_nonce", nullable = false, length = 43, updatable = false, unique = true)
    private String tokenNonce;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false, unique = true)
    private String tokenHash;

    @Column(name = "idempotency_key", nullable = false, updatable = false, unique = true)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private IdentityEmailStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT now()")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected IdentityEmailOutbox() {
        // Para JPA.
    }

    private IdentityEmailOutbox(Long userId, IdentityEmailPurpose purpose, String recipientEmail,
            String tokenNonce, String tokenHash, String idempotencyKey) {
        this.userId = Objects.requireNonNull(userId, "userId es obligatorio");
        this.purpose = Objects.requireNonNull(purpose, "purpose es obligatorio");
        this.recipientEmail = Objects.requireNonNull(recipientEmail, "recipientEmail es obligatorio");
        this.tokenNonce = Objects.requireNonNull(tokenNonce, "tokenNonce es obligatorio");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash es obligatorio");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey es obligatorio");
        this.status = IdentityEmailStatus.PENDING;
        this.attempts = 0;
    }

    /**
     * Encola una emisión y devuelve la fila {@code PENDING}. El productor ha emitido ya el token
     * (con {@code IdentityLinkTokens}) y ha persistido su hash en la tabla de emisión; aquí solo se
     * guarda la referencia de reconstrucción (nonce) y el hash.
     */
    public static IdentityEmailOutbox enqueue(Long userId, IdentityEmailPurpose purpose,
            String recipientEmail, String tokenNonce, String tokenHash, String idempotencyKey, Instant now) {
        IdentityEmailOutbox row = new IdentityEmailOutbox(userId, purpose, recipientEmail, tokenNonce, tokenHash,
                idempotencyKey);
        row.createdAt = Objects.requireNonNull(now, "now es obligatorio");
        return row;
    }

    /**
     * Reserva la fila antes de abrir SMTP: suma un intento y la bloquea con un lease. El claim no
     * equivale a un intento de red; la transacción corta termina antes del envío.
     */
    public void markClaimStarted(Instant leaseUntil, Instant now) {
        requireDispatchable();
        this.attempts = this.attempts + 1;
        this.leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil es obligatorio");
        this.updatedAt = Objects.requireNonNull(now, "now es obligatorio");
    }

    /** SENT: el proveedor confirmó el envío (única transición terminal de éxito). */
    public void recordSent(Instant now) {
        requireDispatchable();
        this.status = IdentityEmailStatus.SENT;
        this.sentAt = Objects.requireNonNull(now, "now es obligatorio");
        this.lastError = null;
        this.nextAttemptAt = null;
        this.leaseUntil = null;
        this.updatedAt = now;
    }

    /** Fallo con reintentos disponibles: se conserva PENDING y se programa el siguiente intento. */
    public void scheduleRetry(String error, Instant nextAttemptAt, Instant now) {
        requireDispatchable();
        this.lastError = error;
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt es obligatorio");
        this.leaseUntil = null;
        this.updatedAt = Objects.requireNonNull(now, "now es obligatorio");
    }

    /** Fallo definitivo: se agotaron los intentos; queda registrada para intervención. */
    public void markFailed(String error, Instant now) {
        requireDispatchable();
        this.status = IdentityEmailStatus.FAILED;
        this.lastError = error;
        this.nextAttemptAt = null;
        this.leaseUntil = null;
        this.updatedAt = Objects.requireNonNull(now, "now es obligatorio");
    }

    /**
     * Invalida la entrega por una emisión posterior (marca, no estado). Un envío ya confirmado
     * ({@code SENT}) es terminal y no se invalida.
     */
    public void markSuperseded(Instant now) {
        Objects.requireNonNull(now, "now es obligatorio");
        if (this.status == IdentityEmailStatus.SENT || this.supersededAt != null) {
            return;
        }
        this.supersededAt = now;
        this.nextAttemptAt = null;
        this.leaseUntil = null;
        this.updatedAt = now;
    }

    /** ¿La fila sigue reservada por este intento? Evita que un worker obsoleto pise el resultado. */
    public boolean ownsReservation(int expectedAttempts, Instant expectedLeaseUntil) {
        return this.attempts == expectedAttempts
                && Objects.equals(this.leaseUntil, expectedLeaseUntil);
    }

    private void requireDispatchable() {
        if (this.status != IdentityEmailStatus.PENDING || this.supersededAt != null) {
            throw new IllegalStateException(
                    "Solo una entrega PENDING vigente puede cambiar de estado");
        }
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public IdentityEmailPurpose getPurpose() {
        return purpose;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public String getTokenNonce() {
        return tokenNonce;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public IdentityEmailStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
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

    @Override
    public String toString() {
        return "IdentityEmailOutbox{id=" + id + ", purpose=" + purpose + ", status=" + status
                + ", attempts=" + attempts + '}';
    }
}

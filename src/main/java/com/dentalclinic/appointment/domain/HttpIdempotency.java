package com.dentalclinic.appointment.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Idempotencia de PETICIÓN HTTP (A-16/O-2), no de envío.
 *
 * <p>Registra la {@code Idempotency-Key} de una petición de usuario (actor + operación) con el hash
 * del payload y la respuesta devuelta. La clave es única POR ACTOR Y OPERACIÓN (no global) y se
 * consume de forma ATÓMICA con la operación de negocio: se inserta en la misma transacción, así que
 * si la cita hace rollback la clave no queda consumida.
 */
@Entity
@Table(name = "http_idempotency")
public class HttpIdempotency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;

    @Column(name = "operation", nullable = false, length = 60)
    private String operation;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body", columnDefinition = "jsonb")
    private Map<String, Object> responseBody;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT now()")
    private Instant createdAt;

    protected HttpIdempotency() {
        // Para JPA.
    }

    private HttpIdempotency(Long actorUserId, String operation, String idempotencyKey,
                            String requestHash, int responseStatus,
                            Map<String, Object> responseBody) {
        this.actorUserId = Objects.requireNonNull(actorUserId, "actorUserId es obligatorio");
        this.operation = Objects.requireNonNull(operation, "operation es obligatorio");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey es obligatorio");
        this.requestHash = Objects.requireNonNull(requestHash, "requestHash es obligatorio");
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
    }

    public static HttpIdempotency create(Long actorUserId, String operation, String idempotencyKey,
                                         String requestHash, int responseStatus,
                                         Map<String, Object> responseBody) {
        return new HttpIdempotency(actorUserId, operation, idempotencyKey, requestHash,
                responseStatus, responseBody);
    }

    public Long getId() {
        return id;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public String getOperation() {
        return operation;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public int getResponseStatus() {
        return responseStatus;
    }

    public Map<String, Object> getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
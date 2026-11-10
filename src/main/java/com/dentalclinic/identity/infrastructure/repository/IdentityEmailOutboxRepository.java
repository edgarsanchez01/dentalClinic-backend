package com.dentalclinic.identity.infrastructure.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dentalclinic.identity.domain.IdentityEmailOutbox;
import com.dentalclinic.identity.domain.IdentityEmailPurpose;

import jakarta.persistence.LockModeType;

/**
 * Acceso a la outbox de identidad (F8B.2). El claim usa {@code FOR UPDATE SKIP LOCKED} para que dos
 * dispatchers concurrentes nunca reclamen la misma fila (Architecture.md 9.1/9.4).
 */
public interface IdentityEmailOutboxRepository extends JpaRepository<IdentityEmailOutbox, Long> {

    Optional<IdentityEmailOutbox> findByIdempotencyKey(String idempotencyKey);

    Optional<IdentityEmailOutbox> findByTokenHash(String tokenHash);

    boolean existsByUserIdAndPurposeAndCreatedAtAfter(Long userId, IdentityEmailPurpose purpose, Instant since);

    long countByUserIdAndPurposeAndCreatedAtAfter(Long userId, IdentityEmailPurpose purpose, Instant since);

    /** Serializa la comprobación de reserva y la actualización frente a claim/reemisión/resultados. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM IdentityEmailOutbox o WHERE o.id = :id")
    Optional<IdentityEmailOutbox> findLockedById(@Param("id") Long id);

    /**
     * Invalida la entrega vigente de una cuenta y propósito, si existe, de forma inmediata.
     *
     * <p>Es una sentencia masiva ({@code @Modifying}) y no un cambio por entidad a propósito: al
     * reemitir, Hibernate ordenaría el INSERT de la nueva fila antes del UPDATE que marca
     * {@code superseded_at} de la vieja, y ambas chocarían contra el índice parcial UNIQUE
     * {@code identity_email_outbox_active_idx}. La sentencia masiva se ejecuta al momento y libera
     * el hueco antes del INSERT.
     *
     * @return número de entregas invalidadas (0 o 1 por el índice parcial)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE IdentityEmailOutbox o
               SET o.supersededAt = :now,
                   o.nextAttemptAt = null,
                   o.leaseUntil = null,
                   o.updatedAt = :now
             WHERE o.userId = :userId
               AND o.purpose = :purpose
               AND o.status = com.dentalclinic.identity.domain.IdentityEmailStatus.PENDING
               AND o.supersededAt IS NULL
            """)
    int supersedeActive(@Param("userId") Long userId, @Param("purpose") IdentityEmailPurpose purpose,
            @Param("now") Instant now);

    /**
     * Claim atómico: selecciona y bloquea las filas {@code PENDING} vigentes y elegibles (sin lease
     * activo y con {@code next_attempt_at} vencido o nulo). El bloqueo se libera al commit de la
     * transacción corta; el envío SMTP ocurre después, fuera de toda transacción.
     */
    @Query(value = """
            SELECT id FROM identity_email_outbox
            WHERE status = 'PENDING'
              AND superseded_at IS NULL
              AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
              AND (lease_until IS NULL OR lease_until <= :now)
            ORDER BY next_attempt_at ASC NULLS FIRST, id ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockClaimable(@Param("now") Instant now, @Param("batchSize") int batchSize);
}

package com.dentalclinic.identity.infrastructure.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dentalclinic.identity.domain.RefreshSession;

/**
 * Persistencia de las sesiones de refresh (P-02 CLOSED, Architecture.md §7.1).
 *
 * <p>El consumo atómico es la pieza crítica: dos renovaciones simultáneas del mismo token
 * <strong>no pueden validarse ambas</strong>. No se resuelve con un
 * {@code SELECT} seguido de un {@code UPDATE}, sino con un único {@code UPDATE} condicional
 * (compare-and-set) sobre el índice único de {@code token_hash}: solo la primera petición
 * que cumple la condición afecta a una fila; la segunda se lleva 0 filas.
 */
public interface RefreshSessionRepository extends JpaRepository<RefreshSession, Long> {

    Optional<RefreshSession> findByTokenHash(String tokenHash);

    List<RefreshSession> findByFamilyIdOrderByIssuedAtAsc(UUID familyId);

    List<RefreshSession> findByUserIdAndRevokedAtIsNull(Long userId);

    /**
     * Consumo atómico del token vigente de una familia.
     *
     * @return 1 si este hilo ganó el consume; 0 si el token ya estaba rotado, revocado,
     *         expirado o no existe
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefreshSession s
               SET s.rotatedAt = :now
             WHERE s.tokenHash = :tokenHash
               AND s.rotatedAt IS NULL
               AND s.revokedAt IS NULL
               AND s.expiresAt > :now
            """)
    int consumeAtomically(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    /** Revoca la familia completa. Es la respuesta al <em>reuse detection</em> (§7.1). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshSession s SET s.revokedAt = :now WHERE s.familyId = :familyId AND s.revokedAt IS NULL")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    /**
     * Kill-switch de P-02: {@code user.active = false} invalida todas las sesiones y familias
     * activas del usuario.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshSession s SET s.revokedAt = :now WHERE s.userId = :userId AND s.revokedAt IS NULL")
    int revokeAllForUser(@Param("userId") Long userId, @Param("now") Instant now);

    /** Token ya rotado de la misma familia: prueba de reutilización. */
    @Query("""
            SELECT COUNT(s) > 0
              FROM RefreshSession s
             WHERE s.tokenHash = :tokenHash
               AND s.rotatedAt IS NOT NULL
            """)
    boolean wasAlreadyRotated(@Param("tokenHash") String tokenHash);
}
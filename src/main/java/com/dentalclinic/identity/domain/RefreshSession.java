package com.dentalclinic.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Refresh token opaco, hasheado, rotativo y agrupado por familia.
 *
 * <p><strong>Estructura elegida.</strong> Architecture.md §12.7 declara expresamente
 * que en ese apartado <em>no</em> se fijan los nombres de columnas, el algoritmo de
 * hash ni la estructura de la tabla: son detalles de implementación. Lo normativo es
 * el comportamiento observable de §7.1 y P-02 (CLOSED):
 *
 * <ul>
 *   <li>una familia por login; N sesiones independientes por usuario;</li>
 *   <li>rotación en <em>cada</em> uso; el token anterior queda rotado y no es reutilizable;</li>
 *   <li><em>reuse detection</em>: reutilizar un token ya rotado revoca la familia completa y
 *       responde de forma neutra;</li>
 *   <li>consumo atómico: dos renovaciones simultáneas no pueden validarse ambas;</li>
 *   <li>logout revoca la familia actual y no afecta a las demás sesiones del usuario;</li>
 *   <li>{@code user.active = false} invalida las sesiones/familias activas.</li>
 * </ul>
 *
 * <p>Hay una fila por refresh token emitido, y todas las filas de una sesión comparten
 * {@code family_id}. Así el <em>reuse detection</em> detecta la reutilización de un token de
 * <strong>cualquier</strong> generación, no solo del inmediatamente anterior, y el consumo sigue
 * siendo un único compare-and-set sobre la fila del token vigente.
 *
 * <p>El consumo atómico se apoya en el índice único de {@code token_hash}:
 *
 * <pre>
 * UPDATE refresh_sessions SET rotated_at = now()
 *  WHERE token_hash = :hash AND rotated_at IS NULL AND revoked_at IS NULL AND expires_at &gt; now()
 * </pre>
 *
 * <p>Si afecta a 0 filas, ese token no es el vigente de su familia.
 */
@Entity
@Table(name = "refresh_sessions")
public class RefreshSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false, unique = true)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** {@code null} = este es el token vigente de la familia. */
    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT now()")
    private Instant createdAt;

    protected RefreshSession() {
        // Para JPA.
    }

    private RefreshSession(Long userId, UUID familyId, String tokenHash, Instant issuedAt,
            Instant expiresAt) {
        this.userId = Objects.requireNonNull(userId, "userId es obligatorio");
        this.familyId = Objects.requireNonNull(familyId, "familyId es obligatorio");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash es obligatorio");
        this.issuedAt = Objects.requireNonNull(issuedAt, "issuedAt es obligatorio");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt es obligatorio");
    }

    /** Abre una familia nueva: una sesión / un dispositivo. */
    public static RefreshSession startFamily(Long userId, String tokenHash, Instant now,
            Instant expiresAt) {
        return new RefreshSession(userId, UUID.randomUUID(), tokenHash, now, expiresAt);
    }

    /** Emite el siguiente token de la misma familia tras una rotación validada. */
    public static RefreshSession rotate(Long userId, UUID familyId, String newTokenHash, Instant now,
            Instant expiresAt) {
        return new RefreshSession(userId, familyId, newTokenHash, now, expiresAt);
    }

    public void markRotatedAt(Instant instant) {
        this.rotatedAt = Objects.requireNonNull(instant, "instant es obligatorio");
    }

    /** Revocación de la familia completa (reuse detection, logout, kill-switch). */
    public void revoke(Instant instant) {
        this.revokedAt = Objects.requireNonNull(instant, "instant es obligatorio");
    }

    public boolean isLiveTokenAt(Instant now) {
        return rotatedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }

    public boolean belongsTo(Long candidateUserId, UUID candidateFamilyId) {
        return userId.equals(candidateUserId) && familyId.equals(candidateFamilyId);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRotatedAt() {
        return rotatedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    @Override
    public String toString() {
        return "RefreshSession{id=" + id + ", userId=" + userId + ", familyId=" + familyId
                + ", rotated=" + (rotatedAt != null) + ", revoked=" + (revokedAt != null) + "}";
    }
}
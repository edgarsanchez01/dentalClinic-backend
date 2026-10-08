package com.dentalclinic.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Token de recuperación de contraseña. Hasheado, expirable y de un solo uso.
 *
 * <p>RF-IA-12 y Architecture.md §24.6: "Tokens (invitación, reset) hasheados;
 * expirables y de un solo uso." El token en claro nunca se persiste ni se registra.
 *
 * <p>RF-IA-12 y P-03 (CLOSED): la recuperación responde de forma <strong>neutra</strong>;
 * nunca revela si el email existe. Por eso la existencia del token no cambia la
 * respuesta HTTP.
 */
@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT now()")
    private Instant createdAt;

    protected PasswordResetToken() {
        // Para JPA.
    }

    private PasswordResetToken(Long userId, String tokenHash, Instant expiresAt) {
        this.userId = Objects.requireNonNull(userId, "userId es obligatorio");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash es obligatorio");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt es obligatorio");
    }

    public static PasswordResetToken issue(Long userId, String tokenHash, Instant expiresAt) {
        return new PasswordResetToken(userId, tokenHash, expiresAt);
    }

    public void markUsedAt(Instant instant) {
        this.usedAt = Objects.requireNonNull(instant, "instant es obligatorio");
    }

    public boolean isUsableAt(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Optional<Instant> getUsedAt() {
        return Optional.ofNullable(usedAt);
    }

    @Override
    public String toString() {
        return "PasswordResetToken{id=" + id + ", used=" + (usedAt != null) + "}";
    }
}
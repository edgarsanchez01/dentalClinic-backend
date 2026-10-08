package com.dentalclinic.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Invitación de activación. Token hasheado, expirable y de un solo uso.
 *
 * <p>Architecture.md §15.2: "Invitación de personal (activación). Token hasheado,
 * expirable, un solo uso." RF-IA-09 lo exige como norma.
 *
 * <p>P-03 (CLOSED): la activación por token de un solo uso es el único camino de
 * alta ligado a una invitación, y no existe verificación de email.
 *
 * <p><strong>Alcance de Fase 2:</strong> se implementa el lado del <em>consumidor</em>
 * ({@code POST /api/auth/activate}). El emisor —el alta de dentistas y
 * recepcionistas por el OWNER— es Fase 3, y el alta de un paciente por recepción es
 * Fase 4. En Fase 2 no existe ningún endpoint que emita invitaciones.
 *
 * <p><strong>Los dos modos de alta que describen los documentos normativos, en una sola
 * entidad:</strong>
 *
 * <ul>
 *   <li><strong>Cuenta ya creada, pendiente de activar</strong> — PRD §6.5.2 opción A (la
 *       recomendada para el MVP): el emisor crea {@code User(active = false)} junto al resto de
 *       datos del profesional y la invitación solo aporta la prueba de que quien la presenta es
 *       quien debe activar esa cuenta. Es el modo de <em>personal</em>.</li>
 *   <li><strong>Cuenta creada al activar</strong> — el flujo dibujado en P-03 para el paciente:
 *       recepción crea el {@code Patient} administrativo, la invitación lleva el email y el rol, y
 *       la activación da de alta el {@code User}. Es el modo de <em>paciente</em>.</li>
 * </ul>
 *
 * <p>Ambos conviven porque {@code user_id} admite nulo: {@code null} significa "la cuenta se crea
 * al activar" y un valor presente significa "esa cuenta es la que hay que activar". La emisión de
 * invitaciones pertenece a fases posteriores; en Fase 2 solo se implementa el consumo.
 *
 * <p><strong>P-07 (OPEN):</strong> el CHECK de la base de datos excluye
 * {@code OWNER} del rol que una invitación puede otorgar. No es una política sobre
 * múltiples OWNER: es la defensa en profundidad de la restricción vigente de que la
 * instalación tiene un único OWNER, el del onboarding inicial.
 */
@Entity
@Table(name = "invitations")
public class Invitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false, unique = true)
    private String tokenHash;

    @Column(name = "email", nullable = false, length = 320, updatable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20, updatable = false)
    private Role role;

    @Column(name = "user_id", unique = true)
    private Long userId;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT now()")
    private Instant createdAt;

    protected Invitation() {
        // Para JPA.
    }

    private Invitation(String tokenHash, String email, Role role, Long userId, Instant expiresAt) {
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash es obligatorio");
        this.email = Objects.requireNonNull(email, "email es obligatorio");
        this.role = Objects.requireNonNull(role, "role es obligatorio");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt es obligatorio");
        this.userId = userId;
    }

    private static Invitation of(String tokenHash, String email, Role role, Long userId, Instant expiresAt) {
        if (role == Role.OWNER) {
            throw new IllegalArgumentException(
                    "P-07 sigue OPEN: no se emite ninguna invitación con rol OWNER");
        }
        return new Invitation(tokenHash, email, role, userId, expiresAt);
    }

    /** La cuenta se crea al activar (modo paciente, flujo P-03). */
    public static Invitation issue(String tokenHash, String email, Role role, Instant expiresAt) {
        return of(tokenHash, email, role, null, expiresAt);
    }

    /** La cuenta ya existe y solo hay que activarla (modo personal, PRD §6.5.2 opción A). */
    public static Invitation issueForExistingUser(String tokenHash, String email, Role role, Long userId,
            Instant expiresAt) {
        return of(tokenHash, email, role, Objects.requireNonNull(userId, "userId es obligatorio"), expiresAt);
    }

    /** Consumir la invitación es una transición terminal: solo puede ocurrir una vez. */
    public void markUsedAt(Instant instant, Long activatedUserId) {
        this.usedAt = Objects.requireNonNull(instant, "instant es obligatorio");
        Objects.requireNonNull(activatedUserId, "activatedUserId es obligatorio");
        if (this.userId != null && !this.userId.equals(activatedUserId)) {
            throw new IllegalStateException(
                    "La invitación ya estaba vinculada a otra cuenta y no puede reasignarse");
        }
        this.userId = activatedUserId;
    }

    public boolean isUsableAt(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    public Long getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getEmail() {
        return email;
    }

    public Role getRole() {
        return role;
    }

    /** @return la cuenta que hay que activar, o vacío si se creará al activar */
    public Optional<Long> getUserId() {
        return Optional.ofNullable(userId);
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Optional<Instant> getUsedAt() {
        return Optional.ofNullable(usedAt);
    }

    @Override
    public String toString() {
        return "Invitation{id=" + id + ", role=" + role + ", used=" + (usedAt != null) + "}";
    }
}
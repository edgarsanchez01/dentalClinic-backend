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
 * Identidad y acceso: email, hash de contraseña, rol y activo.
 *
 * <p>Architecture.md §15.2 define exactamente estos atributos para {@code User}.
 * No se añaden aquí nombres, apellidos, teléfono ni otros datos personales: esos
 * viven en los agregados que los necesitan ({@code Dentist} en Fase 3,
 * {@code Patient} completo en Fase 4) y añadirlos sería inventar modelo.
 *
 * <p>Invariantes:
 * <ul>
 *   <li>{@code role} nunca es nulo (RF-IA-16, AD-18).</li>
 *   <li>{@code email} es único en la instalación (Architecture.md §12.7).</li>
 *   <li>La contraseña nunca sale de esta clase en claro: solo el hash viaja a la
 *       base de datos y nunca a un DTO de respuesta ni a un log (§7.7).</li>
 *   <li>{@code active} significa "cuenta activada", NO "email verificado": el
 *       producto no tiene verificación de email (P-03 CLOSED).</li>
 * </ul>
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, length = 320, updatable = false)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT now()")
    private Instant createdAt;

    protected User() {
        // Para JPA.
    }

    private User(String email, String passwordHash, Role role, boolean active) {
        this.email = Objects.requireNonNull(email, "email es obligatorio");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash es obligatorio");
        this.role = Objects.requireNonNull(role, "role es obligatorio: no existe usuario sin rol");
        this.active = active;
    }

    /**
     * Crea una cuenta activada. Es el único constructor público: toda alta de la
     * instalación nace con un rol concreto y con su hash de contraseña.
     */
    public static User create(String email, String passwordHash, Role role) {
        return new User(email, passwordHash, role, true);
    }

    /**
     * Crea una cuenta pendiente de activar (RF-IA-09 / §6.5.2 opción A: el
     * profesional se crea con {@code active=false} y activa su cuenta con el
     * token de invitación de un solo uso).
     */
    public static User createPendingActivation(String email, String passwordHash, Role role) {
        return new User(email, passwordHash, role, false);
    }

    public void activate() {
        this.active = true;
    }

    /**
     * Kill-switch del acceso (§7.1): {@code user.active = false} invalida todas las sesiones y
     * familias activas del usuario en la siguiente petición. La administración que desactiva
     * cuentas es de una fase posterior (gestión de equipo, Fase 3); aquí la operación existe en
     * el dominio porque la norma la garantiza como comportamiento observable.
     */
    public void deactivate() {
        this.active = false;
    }

    public void changePasswordHash(String newPasswordHash) {
        this.passwordHash = Objects.requireNonNull(newPasswordHash, "passwordHash es obligatorio");
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * {@code toString()} deliberadamente sin email ni hash: las entidades nunca
     * exponen secretos en logs (§7.7, Regla 5 del trabajo manual).
     */
    @Override
    public String toString() {
        return "User{id=" + id + ", role=" + role + ", active=" + active + "}";
    }
}
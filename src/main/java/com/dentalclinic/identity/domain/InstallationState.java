package com.dentalclinic.identity.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Marker técnico de instalación (B-04, Architecture.md §15.5).
 *
 * <p><strong>No es un dato de negocio.</strong> No es {@code User}, ni {@code Clinic}, ni
 * {@code Dentist}, ni horario, ni cita, ni pago, y no habilita ninguna funcionalidad por sí mismo:
 * solo registra que el onboarding se completó. El estado de onboarding {@code NO} se deriva de la
 * presencia de {@code User} ni de {@code Clinic} (PRD.md §6.2), precisamente para no confundir
 * "hay un usuario" con "la instalación está configurada".
 *
 * <p>La tabla admite <strong>una sola fila</strong> ({@code id = 1} con CHECK y PK). La ausencia de
 * fila representa {@link InstallationStatus#AWAITING_FIRST_USER}; la fila se inserta como
 * {@link InstallationStatus#COMPLETED} dentro de la misma transacción que crea la clínica y su
 * OWNER. El onboarding toma además {@code pg_advisory_xact_lock} para serializar intentos
 * simultáneos: la fila única es la invariante persistente y el lock es la exclusión mutua.
 */
@Entity
@Table(name = "installation_state")
public class InstallationState {

    /** Única fila posible (constraint {@code CHECK (id = 1)}). */
    public static final short SINGLETON_ID = 1;

    @Id
    @Column(name = "id", nullable = false)
    private Short id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InstallationStatus status;

    @Column(name = "configured_at", nullable = false)
    private Instant configuredAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT now()")
    private Instant createdAt;

    protected InstallationState() {
        // Para JPA.
    }

    private InstallationState(Short id, InstallationStatus status, Instant configuredAt) {
        this.id = Objects.requireNonNull(id, "id es obligatorio");
        this.status = Objects.requireNonNull(status, "status es obligatorio");
        this.configuredAt = Objects.requireNonNull(configuredAt, "configuredAt es obligatorio");
    }

    /** Marker resultante de completar el onboarding. */
    public static InstallationState completed(Instant configuredAt) {
        return new InstallationState(SINGLETON_ID, InstallationStatus.COMPLETED, configuredAt);
    }

    public Short getId() {
        return id;
    }

    public InstallationStatus getStatus() {
        return status;
    }

    public Instant getConfiguredAt() {
        return configuredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
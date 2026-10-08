package com.dentalclinic.clinic.domain;

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
 * Configuración única de la clínica (RF-CL-01..06).
 *
 * <p><strong>Una por instalación.</strong> Solo el onboarding la crea, dentro de la transacción
 * serializada por el marker de instalación ({@code installation_state}). No hay {@code clinicId} en
 * seguridad ni multi-tenancy (A-02).
 *
 * <p>Campos según RF-CL-02 (nombre, descripción, logo, dirección, teléfono, email, redes sociales,
 * información pública, configuración general, zona horaria) y Design System §2.3/§2.4 (color
 * primario configurable). {@code brandColor} es el color base hex del que el frontend deriva la
 * rampa {@code --primary}; si es {@code null} se usa {@code --primary-default}.
 *
 * <p>{@code timezone} es la única fuente de "día" y "horario" (Architecture.md §15.4); se valida
 * como IANA y nunca se cae al default de la JVM (RF-CL-05).
 */
@Entity
@Table(name = "clinic")
public class Clinic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "logo_url", length = 2048)
    private String logoUrl;

    @Column(name = "address")
    private String address;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "email", length = 320)
    private String email;

    @Column(name = "public_info")
    private String publicInfo;

    @Column(name = "brand_color", length = 7)
    private String brandColor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "social_networks", columnDefinition = "jsonb")
    private Map<String, String> socialNetworks;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    /**
     * BookingPolicy (Fase 5, RF-AV-07/08/11). Vive como columnas de la clínica porque es
     * configuración única de instalación; no hay tabla ni JSONB. Los valores por defecto
     * coinciden con el {@code DEFAULT} de V005.
     */
    @Column(name = "max_advance_days", nullable = false)
    private int maxAdvanceDays = 90;

    @Column(name = "min_booking_lead_time_hours", nullable = false)
    private int minBookingLeadTimeHours = 2;

    @Column(name = "cancellation_lead_time_hours", nullable = false)
    private int cancellationLeadTimeHours = 12;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT now()")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Clinic() {
        // Para JPA.
    }

    private Clinic(String name, String timezone, String description, String logoUrl, String address,
                   String phone, String email, String publicInfo, String brandColor,
                   Map<String, String> socialNetworks) {
        this.name = Objects.requireNonNull(name, "name es obligatorio");
        this.timezone = Objects.requireNonNull(timezone, "timezone es obligatorio");
        this.description = description;
        this.logoUrl = logoUrl;
        this.address = address;
        this.phone = phone;
        this.email = email;
        this.publicInfo = publicInfo;
        this.brandColor = brandColor;
        this.socialNetworks = socialNetworks;
    }

    public static Clinic create(String name, String timezone, String description, String logoUrl,
            String address, String phone, String email, String publicInfo, String brandColor,
            Map<String, String> socialNetworks) {
        return new Clinic(name, timezone, description, logoUrl, address, phone, email, publicInfo,
                brandColor, socialNetworks);
    }

    /**
     * Reemplaza la configuración (RF-CL-03, solo OWNER). {@code null} en un campo opcional lo
     * limpia; {@code name} y {@code timezone} son obligatorios.
     */
    public void update(String name, String timezone, String description, String logoUrl,
            String address, String phone, String email, String publicInfo, String brandColor,
            Map<String, String> socialNetworks, Instant updatedAt) {
        this.name = Objects.requireNonNull(name, "name es obligatorio");
        this.timezone = Objects.requireNonNull(timezone, "timezone es obligatorio");
        this.description = description;
        this.logoUrl = logoUrl;
        this.address = address;
        this.phone = phone;
        this.email = email;
        this.publicInfo = publicInfo;
        this.brandColor = brandColor;
        this.socialNetworks = socialNetworks;
        this.updatedAt = updatedAt;
    }

    /** Actualiza la política de reservas (RF-AV-07/08/11). No toca el resto de la configuración. */
    public void updateBookingPolicy(int maxAdvanceDays, int minBookingLeadTimeHours,
            int cancellationLeadTimeHours, Instant updatedAt) {
        this.maxAdvanceDays = maxAdvanceDays;
        this.minBookingLeadTimeHours = minBookingLeadTimeHours;
        this.cancellationLeadTimeHours = cancellationLeadTimeHours;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getMaxAdvanceDays() {
        return maxAdvanceDays;
    }

    public int getMinBookingLeadTimeHours() {
        return minBookingLeadTimeHours;
    }

    public int getCancellationLeadTimeHours() {
        return cancellationLeadTimeHours;
    }

    public String getDescription() {
        return description;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public String getAddress() {
        return address;
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public String getPublicInfo() {
        return publicInfo;
    }

    public String getBrandColor() {
        return brandColor;
    }

    public Map<String, String> getSocialNetworks() {
        return socialNetworks;
    }

    public String getTimezone() {
        return timezone;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
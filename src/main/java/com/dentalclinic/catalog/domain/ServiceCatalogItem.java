package com.dentalclinic.catalog.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Servicio administrativo previsto; no es un tratamiento clínico ni un cargo/pago. */
@Entity
@Table(name = "service_catalog_item")
public class ServiceCatalogItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(length = 4000)
    private String description;
    @Column(name = "reference_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal referencePrice;
    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected ServiceCatalogItem() { }

    public static ServiceCatalogItem create(String name, String description, BigDecimal price,
                                             int durationMinutes, Instant now) {
        ServiceCatalogItem item = new ServiceCatalogItem();
        item.setDetails(name, description, price, durationMinutes);
        item.active = true;
        item.createdAt = now;
        return item;
    }

    public void update(String name, String description, BigDecimal price, int durationMinutes, Instant now) {
        setDetails(name, description, price, durationMinutes);
        updatedAt = now;
    }

    public void setActive(boolean active, Instant now) {
        this.active = active;
        updatedAt = now;
    }

    private void setDetails(String name, String description, BigDecimal price, int duration) {
        String normalizedName = name == null ? "" : name.strip();
        String normalizedDescription = description == null || description.isBlank() ? null : description.strip();
        if (normalizedName.isEmpty() || normalizedName.length() > 200) {
            throw new InvalidCatalogItemException("El nombre es obligatorio y admite hasta 200 caracteres.");
        }
        if (normalizedDescription != null && normalizedDescription.length() > 4000) {
            throw new InvalidCatalogItemException("La descripción admite hasta 4000 caracteres.");
        }
        if (price == null || price.signum() < 0 || price.scale() > 4
                || price.precision() - price.scale() > 15) {
            throw new InvalidCatalogItemException("El precio de referencia debe ser no negativo, con hasta 15 enteros y 4 decimales.");
        }
        if (duration <= 0) throw new InvalidCatalogItemException("La duración debe ser mayor que cero.");
        this.name = normalizedName;
        this.description = normalizedDescription;
        this.referencePrice = price;
        this.durationMinutes = duration;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public BigDecimal getReferencePrice() { return referencePrice; }
    public int getDurationMinutes() { return durationMinutes; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

package com.dentalclinic.financial.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Concepto o servicio de un {@link Charge}. Es un <strong>snapshot historico</strong>.
 *
 * <ul>
 *   <li>{@code description} e importe quedan congelados; NO se recalculan desde el catalogo.</li>
 *   <li>{@code serviceCatalogItemId} es solo una referencia tecnica opcional al catalogo.</li>
 *   <li>{@code treatingDentistId} es la relacion del profesional que presto el concepto
 *       (opcional). NO es por si sola fuente de autorizacion.</li>
 *   <li>{@code lineTotal} = {@code quantity * unitPrice}, exacto.</li>
 * </ul>
 *
 * <p>No expone setters: la inmutabilidad tras confirmar el cargo se garantiza en aplicacion
 * (el proyecto no usa triggers). La FK de base es {@code ON DELETE RESTRICT}: un cargo con
 * conceptos no puede borrarse.
 */
@Entity
@Table(name = "charge_item")
public class ChargeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "charge_id", nullable = false, updatable = false)
    private Long chargeId;

    @Column(name = "service_catalog_item_id", updatable = false)
    private Long serviceCatalogItemId;

    @Column(name = "treating_dentist_id", updatable = false)
    private Long treatingDentistId;

    @Column(name = "description", nullable = false, length = 4000, updatable = false)
    private String description;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal unitPrice;

    @Column(name = "line_total", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal lineTotal;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChargeItem() {
        // Para JPA.
    }

    /**
     * Crea un concepto del cargo calculando su total de linea.
     *
     * @param serviceCatalogItemId referencia tecnica OPCIONAL al catalogo; puede ser {@code null}
     * @param treatingDentistId    dentista que presto el concepto; puede ser {@code null}
     */
    public static ChargeItem create(Long chargeId, Long serviceCatalogItemId, Long treatingDentistId,
                                    String description, int quantity, BigDecimal unitPrice,
                                    Instant now) {
        if (description == null || description.isBlank()) {
            throw new InvalidChargeException("la descripcion del concepto es obligatoria");
        }
        BigDecimal lineTotal = calculateLineTotal(quantity, unitPrice);
        ChargeItem item = new ChargeItem();
        item.chargeId = Objects.requireNonNull(chargeId, "chargeId es obligatorio");
        item.serviceCatalogItemId = serviceCatalogItemId;
        item.treatingDentistId = treatingDentistId;
        item.description = description;
        item.quantity = quantity;
        item.unitPrice = unitPrice;
        item.lineTotal = lineTotal;
        item.createdAt = Objects.requireNonNull(now, "now es obligatorio");
        return item;
    }

    /** Calculo puro compartido por el alta y la suma del cargo; nunca redondea. */
    public static BigDecimal calculateLineTotal(int quantity, BigDecimal unitPrice) {
        if (quantity <= 0) {
            throw new InvalidChargeException("la cantidad del concepto debe ser positiva");
        }
        BigDecimal total = FinancialAmounts.requireNonNegative(unitPrice, "unitPrice", InvalidChargeException::new)
                .multiply(BigDecimal.valueOf(quantity));
        return FinancialAmounts.requireNonNegative(total, "lineTotal", InvalidChargeException::new);
    }

    public Long getId() {
        return id;
    }

    public Long getChargeId() {
        return chargeId;
    }

    public Long getServiceCatalogItemId() {
        return serviceCatalogItemId;
    }

    public Long getTreatingDentistId() {
        return treatingDentistId;
    }

    public String getDescription() {
        return description;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

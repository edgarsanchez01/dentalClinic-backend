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
import jakarta.persistence.Version;

/**
 * Cargo administrativo confirmado (MVP financiero).
 *
 * <p>Pertenece SIEMPRE a un {@code patient} y se confirma de forma <strong>explicita</strong>
 * ({@code confirmedAt}/{@code confirmedByUserId} obligatorios). Puede vincularse de forma
 * OPCIONAL a una cita ({@code appointmentId} nullable): una cita puede tener cero, uno o
 * varios cargos, y un cargo sin cita es valido.
 *
 * <p><strong>No se genera automaticamente</strong> al reservar ni al completar una cita.
 *
 * <p>No persiste estado financiero (PENDIENTE/PARCIAL/PAGADO/ANULADO) ni saldo: ambos se
 * DERIVAN de {@code totalAmount} y de los movimientos validos. {@code ANULADO} equivale a
 * {@code cancelledAt != null}. Evitar una segunda fuente de verdad es una decision aprobada.
 *
 * <p>La invariante "el total coincide con la suma de sus conceptos" y "la cita asociada es
 * del mismo paciente" se validan en la capa de aplicacion (Fase 2), no aqui.
 */
@Entity
@Table(name = "charge")
public class Charge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "appointment_id")
    private Long appointmentId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Column(name = "notes")
    private String notes;

    @Column(name = "confirmed_at", nullable = false, updatable = false)
    private Instant confirmedAt;

    @Column(name = "confirmed_by_user_id", nullable = false, updatable = false)
    private Long confirmedByUserId;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by_user_id")
    private Long cancelledByUserId;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT now()")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Charge() {
        // Para JPA.
    }

    /**
     * Crea un cargo YA confirmado (el MVP solo admite creacion manual y confirmada).
     *
     * @param appointmentId referencia OPCIONAL a una cita; puede ser {@code null}
     */
    public static Charge createConfirmed(Long patientId, Long appointmentId, BigDecimal totalAmount,
                                         String notes, Long confirmedByUserId, Instant now) {
        Charge charge = new Charge();
        charge.patientId = Objects.requireNonNull(patientId, "patientId es obligatorio");
        charge.appointmentId = appointmentId;
        charge.currency = "MXN";
        charge.totalAmount = FinancialAmounts.requirePositive(
                totalAmount, "totalAmount", InvalidChargeException::new);
        charge.notes = notes;
        charge.confirmedByUserId =
                Objects.requireNonNull(confirmedByUserId, "confirmedByUserId es obligatorio");
        charge.confirmedAt = Objects.requireNonNull(now, "now es obligatorio");
        charge.version = 1;
        return charge;
    }

    /**
     * Anula el cargo (accion administrativa explicita; solo OWNER en la capa de aplicacion).
     * Requiere motivo; no se permite si conserva pagos validos (validado en Fase 2).
     */
    public void cancel(Long cancelledByUserId, String reason, Instant now) {
        if (isCancelled()) {
            throw new InvalidChargeException("el cargo ya esta anulado");
        }
        Objects.requireNonNull(cancelledByUserId, "cancelledByUserId es obligatorio");
        if (reason == null || reason.isBlank()) {
            throw new InvalidChargeException("el motivo de anulacion es obligatorio");
        }
        this.cancelledAt = Objects.requireNonNull(now, "now es obligatorio");
        this.cancelledByUserId = cancelledByUserId;
        this.cancellationReason = reason;
        this.updatedAt = now;
    }

    public boolean isCancelled() {
        return cancelledAt != null;
    }

    public Long getId() {
        return id;
    }

    public Long getPatientId() {
        return patientId;
    }

    public Long getAppointmentId() {
        return appointmentId;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Long getConfirmedByUserId() {
        return confirmedByUserId;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public Long getCancelledByUserId() {
        return cancelledByUserId;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public int getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

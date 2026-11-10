package com.dentalclinic.financial.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Movimiento financiero <strong>inmutable</strong> sobre un {@link Charge}.
 *
 * <ul>
 *   <li>{@code PAYMENT}: abono/pago parcial registrado manualmente. Direccion {@code IN}.
 *       {@code paymentMethod} obligatorio; {@code reason} opcional.</li>
 *   <li>{@code REVERSAL}: compensa UN {@code PAYMENT} previo via {@code reversesMovementId}.
 *       Direccion {@code OUT}. {@code reason} obligatorio.</li>
 * </ul>
 *
 * <p>No se modifica ni se elimina un movimiento historico (no hay {@code updatedAt} ni setters).
 * El original de una reversa permanece intacto. El doble reversado se impide en base con
 * {@code UNIQUE(reverses_movement_id)}.
 *
 * <p>Los tipos {@code ADJUSTMENT}/{@code REFUND} quedan fuera del MVP.
 */
@Entity
@Table(name = "financial_movement")
public class FinancialMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "charge_id", nullable = false, updatable = false)
    private Long chargeId;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private Long patientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private FinancialMovementType type;

    @Convert(converter = MovementDirectionConverter.class)
    @Column(name = "direction", nullable = false, length = 1, updatable = false)
    private MovementDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20, updatable = false)
    private PaymentMethod paymentMethod;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "reason", updatable = false)
    private String reason;

    @Column(name = "reverses_movement_id", updatable = false)
    private Long reversesMovementId;

    @Column(name = "performed_by_user_id", nullable = false, updatable = false)
    private Long performedByUserId;

    @Column(name = "performed_at", nullable = false, updatable = false)
    private Instant performedAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT now()")
    private Instant createdAt;

    protected FinancialMovement() {
        // Para JPA.
    }

    /** Registra un abono/pago parcial manual sobre un cargo confirmado. */
    public static FinancialMovement payment(Long chargeId, Long patientId, PaymentMethod method,
                                            BigDecimal amount, String reason, Long performedByUserId,
                                            Instant now) {
        if (method == null) {
            throw new InvalidFinancialMovementException("el metodo de pago es obligatorio");
        }
        FinancialMovement movement = new FinancialMovement();
        movement.chargeId = Objects.requireNonNull(chargeId, "chargeId es obligatorio");
        movement.patientId = Objects.requireNonNull(patientId, "patientId es obligatorio");
        movement.type = FinancialMovementType.PAYMENT;
        movement.direction = MovementDirection.IN;
        movement.paymentMethod = method;
        movement.amount = FinancialAmounts.requirePositive(
                amount, "amount", InvalidFinancialMovementException::new);
        movement.reason = reason;
        movement.reversesMovementId = null;
        movement.performedByUserId =
                Objects.requireNonNull(performedByUserId, "performedByUserId es obligatorio");
        movement.performedAt = Objects.requireNonNull(now, "now es obligatorio");
        return movement;
    }

    /**
     * Construye la compensacion COMPLETA de un pago previo, sin persistir ni modificar el original.
     * 2B debe cargar el PAYMENT persistido y pasar exactamente su importe, cargo y paciente;
     * esta fabrica de bajo nivel no tiene acceso a la base para comprobar esas relaciones.
     * Motivo obligatorio; no se reversiona un movimiento nulo ni una REVERSAL.
     */
    public static FinancialMovement reversal(Long chargeId, Long patientId, Long reversesMovementId,
                                             BigDecimal amount, String reason, Long performedByUserId,
                                             Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new InvalidFinancialMovementException("el motivo de la reversa es obligatorio");
        }
        if (reversesMovementId == null) {
            throw new InvalidFinancialMovementException("reversesMovementId es obligatorio");
        }
        FinancialMovement movement = new FinancialMovement();
        movement.chargeId = Objects.requireNonNull(chargeId, "chargeId es obligatorio");
        movement.patientId = Objects.requireNonNull(patientId, "patientId es obligatorio");
        movement.type = FinancialMovementType.REVERSAL;
        movement.direction = MovementDirection.OUT;
        movement.paymentMethod = null;
        movement.amount = FinancialAmounts.requirePositive(
                amount, "amount", InvalidFinancialMovementException::new);
        movement.reason = reason;
        movement.reversesMovementId = reversesMovementId;
        movement.performedByUserId =
                Objects.requireNonNull(performedByUserId, "performedByUserId es obligatorio");
        movement.performedAt = Objects.requireNonNull(now, "now es obligatorio");
        return movement;
    }

    public Long getId() {
        return id;
    }

    public Long getChargeId() {
        return chargeId;
    }

    public Long getPatientId() {
        return patientId;
    }

    public FinancialMovementType getType() {
        return type;
    }

    public MovementDirection getDirection() {
        return direction;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReason() {
        return reason;
    }

    public Long getReversesMovementId() {
        return reversesMovementId;
    }

    public Long getPerformedByUserId() {
        return performedByUserId;
    }

    public Instant getPerformedAt() {
        return performedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

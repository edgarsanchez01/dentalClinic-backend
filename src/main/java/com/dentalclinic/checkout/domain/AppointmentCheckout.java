package com.dentalclinic.checkout.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Identidad durable del cierre integrado de UNA cita (UX-05B.1, Etapa 3.2).
 *
 * <p>Es append-only: no tiene setters ni {@code updatedAt}. La unicidad por cita y por movimiento
 * la garantiza PostgreSQL; no depende de una comprobacion previa en Java. Solo el componente
 * interno de aplicacion construye estas filas dentro de una transaccion ambiente.
 *
 * <p>No persiste saldo, estado financiero ni estado de la cita. Las referencias economicas son
 * historicas: {@code chargeId} identifica el cargo del cierre y {@code paymentMovementId}, cuando
 * existe, el unico {@code PAYMENT} nuevo registrado por ese cierre.
 */
// Historical value only. Deliberately NOT a JPA entity: V020 is optional, never a runtime dependency.
public class AppointmentCheckout {

    private Long id;

    private Long appointmentId;

    private int occurrenceVersion;

    private Long chargeId;

    private Long paymentMovementId;

    private ChargeDisposition chargeDisposition;

    private Long actorUserId;

    private Instant completedAt;

    protected AppointmentCheckout() {
        // Para compatibilidad de serializacion historica.
    }

    private AppointmentCheckout(Long appointmentId, int occurrenceVersion, Long chargeId,
            Long paymentMovementId, ChargeDisposition chargeDisposition, Long actorUserId,
            Instant completedAt) {
        this.appointmentId = Objects.requireNonNull(appointmentId, "appointmentId es obligatorio");
        if (occurrenceVersion < 1) {
            throw new IllegalArgumentException("occurrenceVersion debe ser >= 1.");
        }
        this.occurrenceVersion = occurrenceVersion;
        this.chargeId = Objects.requireNonNull(chargeId, "chargeId es obligatorio");
        this.paymentMovementId = paymentMovementId;
        this.chargeDisposition = Objects.requireNonNull(chargeDisposition,
                "chargeDisposition es obligatorio");
        this.actorUserId = Objects.requireNonNull(actorUserId, "actorUserId es obligatorio");
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt es obligatorio");
    }

    /**
     * Construye una fila nueva. No valida relaciones cruzadas: el registrador interno debe
     * comprobar que cita, cargo y movimiento son consistentes antes de llamar aqui.
     */
    public static AppointmentCheckout create(Long appointmentId, int occurrenceVersion, Long chargeId,
            Long paymentMovementId, ChargeDisposition chargeDisposition, Long actorUserId,
            Instant completedAt) {
        return new AppointmentCheckout(appointmentId, occurrenceVersion, chargeId, paymentMovementId,
                chargeDisposition, actorUserId, completedAt);
    }

    public Long getId() {
        return id;
    }

    /** Hydrates existing evidence only; never generates or reinterprets a historical identity. */
    public static AppointmentCheckout historical(Long id, Long appointmentId, int occurrenceVersion,
            Long chargeId, Long paymentMovementId, ChargeDisposition disposition, Long actorId,
            Instant completedAt) {
        var value = new AppointmentCheckout(appointmentId, occurrenceVersion, chargeId,
                paymentMovementId, disposition, actorId, completedAt);
        value.id = Objects.requireNonNull(id);
        return value;
    }

    public Long getAppointmentId() {
        return appointmentId;
    }

    public int getOccurrenceVersion() {
        return occurrenceVersion;
    }

    public Long getChargeId() {
        return chargeId;
    }

    public Long getPaymentMovementId() {
        return paymentMovementId;
    }

    public ChargeDisposition getChargeDisposition() {
        return chargeDisposition;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}

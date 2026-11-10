package com.dentalclinic.financial.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.catalog.application.ServiceCatalogService;
import com.dentalclinic.clinic.application.DentistService;
import com.dentalclinic.financial.application.command.ChargeItemData;
import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.ChargeFinancialState;
import com.dentalclinic.financial.domain.ChargeItem;
import com.dentalclinic.financial.domain.ChargeStatusCalculator;
import com.dentalclinic.financial.domain.FinancialConflictException;
import com.dentalclinic.financial.domain.DefinitiveFinancialConflictException;
import com.dentalclinic.financial.domain.DefinitiveInvalidChargeException;
import com.dentalclinic.financial.domain.FinancialRejectionReason;
import com.dentalclinic.financial.domain.FinancialMovement;
import com.dentalclinic.financial.domain.FinancialMovementType;
import com.dentalclinic.financial.domain.InvalidFinancialMovementException;
import com.dentalclinic.financial.domain.PaymentMethod;
import com.dentalclinic.financial.infrastructure.repository.ChargeItemRepository;
import com.dentalclinic.financial.infrastructure.repository.ChargeRepository;
import com.dentalclinic.financial.infrastructure.repository.FinancialMovementRepository;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/**
 * Nucleo interno de persistencia financiera reutilizable por una transaccion ya abierta.
 *
 * <p><strong>No es una entrada publica de escritura.</strong> Es {@code package-private}: solo
 * otros componentes de {@code financial.application} pueden invocarlo. Cada metodo exige una
 * transaccion ambiente ({@link Propagation#MANDATORY}) y no abre ni confirma transacciones
 * propias. El llamador conserva la responsabilidad de autorizar al actor, de resolver la
 * idempotencia HTTP y de delimitar el commit.
 *
 * <p>La fachada administrativa {@link FinancialOperationsService} sigue siendo la unica entrada
 * publica de los cuatro casos de uso existentes: aplica sus gates, bloquea la clave idempotente y
 * usa este nucleo dentro de su transaccion {@code REQUIRES_NEW}. El futuro cierre integrado podra
 * abrir una unica transaccion y reutilizar exactamente las mismas validaciones y factories, sin
 * encadenar servicios publicos con transacciones independientes.
 *
 * <p>Orden de bloqueos esperado: clave idempotente (si aplica) y despues cargo. Este componente
 * bloquea el cargo con {@link #lockCharge(Long)}; no toma locks asesores ni de cita. Reutiliza
 * {@link ChargeStatusCalculator}, {@link FinancialMovement} y {@link Charge} sin duplicar reglas
 * monetarias, redondeos, impuestos ni monedas.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class FinancialTransactionParticipant {

    private final ChargeRepository charges;
    private final ChargeItemRepository items;
    private final FinancialMovementRepository movements;
    private final ChargeReferenceValidator references;
    private final DentistService dentists;
    private final ServiceCatalogService catalog;

    public FinancialTransactionParticipant(ChargeRepository charges, ChargeItemRepository items,
            FinancialMovementRepository movements, ChargeReferenceValidator references,
            DentistService dentists, ServiceCatalogService catalog) {
        this.charges = charges;
        this.items = items;
        this.movements = movements;
        this.references = references;
        this.dentists = dentists;
        this.catalog = catalog;
    }

    /**
     * Bloquea y devuelve un cargo persistido. No autoriza al actor: el llamador debe aplicar su
     * gate antes de invocar una escritura con la entidad devuelta.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Charge lockCharge(Long chargeId) {
        FinancialTransactionGuard.requireWritable();
        if (chargeId == null) {
            throw new InvalidFinancialMovementException("El cargo es obligatorio.");
        }
        return charges.findByIdForUpdate(chargeId).orElseThrow(NotFoundException::new);
    }

    /**
     * Valida referencias/conceptos y persiste un cargo confirmado con sus conceptos.
     *
     * <p>El total se deriva de los conceptos con {@link ChargeItem#calculateLineTotal}; nunca se
     * acepta un total del cliente. El actor solo aporta la identidad de confirmacion y el alcance
     * de las referencias, ya autorizado por el llamador.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Charge createConfirmed(ChargeDraft draft, AuthenticatedUser actor, Instant now) {
        FinancialTransactionGuard.requireWritable();
        Objects.requireNonNull(draft, "draft es obligatorio");
        Objects.requireNonNull(actor, "actor es obligatorio");
        references.requireValidReferences(draft.patientId(), draft.appointmentId(), actor);
        if (draft.items() == null || draft.items().isEmpty()) {
            throw new DefinitiveInvalidChargeException(FinancialRejectionReason.CHARGE_ITEMS_REQUIRED,
                    "El cargo requiere uno o mas conceptos.");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (ChargeItemData item : draft.items()) {
            total = total.add(ChargeItem.calculateLineTotal(item.quantity(), item.unitPrice()));
            if (item.treatingDentistId() != null) {
                dentists.get(item.treatingDentistId());
            }
            if (item.serviceCatalogItemId() != null) {
                catalog.get(item.serviceCatalogItemId());
            }
        }
        Charge charge = charges.save(Charge.createConfirmed(draft.patientId(), draft.appointmentId(),
                total, draft.notes(), actor.userId(), now));
        for (ChargeItemData item : draft.items()) {
            items.save(ChargeItem.create(charge.getId(), item.serviceCatalogItemId(),
                    item.treatingDentistId(), item.description(), item.quantity(),
                    item.unitPrice(), now));
        }
        return charge;
    }

    /**
     * Registra como maximo un pago nuevo sobre un cargo ya bloqueado. Valida cargo confirmado, no
     * anulado y saldo pendiente antes de persistir; no consume idempotencia HTTP.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public FinancialMovement registerPayment(Charge charge, PaymentMethod method, BigDecimal amount,
            String reason, Long performedByUserId, Instant now) {
        FinancialTransactionGuard.requireWritable();
        Objects.requireNonNull(charge, "charge es obligatorio");
        requireConfirmed(charge);
        if (charge.isCancelled()) {
            throw new DefinitiveFinancialConflictException(FinancialRejectionReason.CHARGE_CANCELLED,
                    "No se puede pagar un cargo anulado.");
        }
        FinancialMovement payment = FinancialMovement.payment(charge.getId(), charge.getPatientId(),
                method, amount, reason, performedByUserId, now);
        ChargeFinancialState state = ChargeStatusCalculator.evaluate(charge.getTotalAmount(),
                applied(charge), false);
        if (payment.getAmount().compareTo(state.pendingBalance()) > 0) {
            throw new DefinitiveFinancialConflictException(FinancialRejectionReason.PAYMENT_EXCEEDS_BALANCE,
                    "El pago supera el saldo pendiente del cargo.");
        }
        return movements.save(payment);
    }

    /**
     * Registra la compensacion completa de un pago previo. Solo {@code OWNER} puede llegar aqui por
     * la fachada administrativa; este nucleo comprueba relaciones y no crea ingresos nuevos.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public FinancialMovement reversePayment(Charge charge, Long movementId, String reason,
            Long performedByUserId, Instant now) {
        FinancialTransactionGuard.requireWritable();
        Objects.requireNonNull(charge, "charge es obligatorio");
        requireReason(reason);
        if (movementId == null) {
            throw new InvalidFinancialMovementException("El pago original es obligatorio.");
        }
        FinancialMovement original = movements.findById(movementId).orElseThrow(NotFoundException::new);
        if (!charge.getId().equals(original.getChargeId())
                || !charge.getPatientId().equals(original.getPatientId())) {
            throw new FinancialConflictException("El pago original no corresponde al cargo y paciente.");
        }
        if (original.getType() != FinancialMovementType.PAYMENT) {
            throw new FinancialConflictException("Solo se puede reversar un PAYMENT, nunca una REVERSAL.");
        }
        if (movements.existsByReversesMovementId(original.getId())) {
            throw new DefinitiveFinancialConflictException(FinancialRejectionReason.PAYMENT_ALREADY_REVERSED,
                    "El pago ya fue reversado.");
        }
        FinancialMovement reversal = FinancialMovement.reversal(charge.getId(), charge.getPatientId(),
                original.getId(), original.getAmount(), reason, performedByUserId, now);
        return movements.save(reversal);
    }

    /**
     * Anula un cargo bloqueado sin pagos validos. No borra conceptos ni movimientos y conserva el
     * historial para las comprobaciones de integridad.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Charge cancelCharge(Charge charge, String reason, Long cancelledByUserId, Instant now) {
        FinancialTransactionGuard.requireWritable();
        Objects.requireNonNull(charge, "charge es obligatorio");
        requireReason(reason);
        if (charge.isCancelled()) {
            throw new DefinitiveFinancialConflictException(FinancialRejectionReason.CHARGE_ALREADY_CANCELLED,
                    "El cargo ya esta anulado.");
        }
        if (applied(charge).signum() != 0) {
            throw new DefinitiveFinancialConflictException(FinancialRejectionReason.CHARGE_HAS_PAYMENTS,
                    "Debes reversar los pagos validos antes de anular el cargo.");
        }
        charge.cancel(cancelledByUserId, reason, now);
        return charge;
    }

    private BigDecimal applied(Charge charge) {
        return movements.sumValidAppliedAmount(charge.getId()).orElse(BigDecimal.ZERO);
    }

    private static void requireConfirmed(Charge charge) {
        if (charge.getConfirmedAt() == null || charge.getConfirmedByUserId() == null) {
            throw new FinancialConflictException("El cargo no esta confirmado.");
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new InvalidFinancialMovementException("El motivo es obligatorio.");
        }
    }

    /**
     * Datos minimos para persistir un cargo confirmado. No incluye idempotencia HTTP ni identidad
     * del cliente: el llamador ya autorizo el alta y conserva su propia clave.
     */
    record ChargeDraft(Long patientId, Long appointmentId, List<ChargeItemData> items, String notes) {
        ChargeDraft {
            if (items != null) {
                items = List.copyOf(items);
            }
        }
    }
}

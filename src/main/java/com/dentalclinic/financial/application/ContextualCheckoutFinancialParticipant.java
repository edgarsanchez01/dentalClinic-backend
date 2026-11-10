package com.dentalclinic.financial.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.appointment.application.AppointmentCompletionParticipant.CheckoutAppointment;
import com.dentalclinic.financial.application.AppointmentFinancialContextService.FinancialContextCharge;
import com.dentalclinic.financial.application.FinancialContextTokenBuilder.AppointmentState;
import com.dentalclinic.financial.application.command.ChargeItemData;
import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.ChargeFinancialState;
import com.dentalclinic.financial.domain.ChargeStatus;
import com.dentalclinic.financial.domain.ChargeStatusCalculator;
import com.dentalclinic.financial.domain.CheckoutPaymentExceedsBalanceException;
import com.dentalclinic.financial.domain.CheckoutChargeAmbiguousException;
import com.dentalclinic.financial.domain.CheckoutEconomicReferenceRequiredException;
import com.dentalclinic.appointment.application.AppointmentCompletionParticipant;
import com.dentalclinic.financial.domain.FinancialConflictException;
import com.dentalclinic.financial.domain.FinancialContextUnavailableException;
import com.dentalclinic.financial.domain.FinancialMovement;
import com.dentalclinic.financial.domain.PaymentMethod;
import com.dentalclinic.financial.infrastructure.repository.ChargeRepository;
import com.dentalclinic.financial.infrastructure.repository.FinancialMovementRepository;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/**
 * Participante financiero contextual reutilizado por UX-05C. Conserva el nombre historico;
 * la escritura de checkout esta retirada y solo el paquete financiero ejecuta cobros administrativos.
 *
 * <p>Reutiliza el nucleo {@link FinancialTransactionParticipant}, las mismas factories y el
 * mismo calculo de saldo; no llama a {@link FinancialOperationsService} ni abre transacciones
 * {@code REQUIRES_NEW}. Todos los metodos exigen la transaccion unica abierta por el coordinador.
 *
 * <p><strong>Bajo locks:</strong> {@link #lockAndVerify} bloquea la cita (ya bloqueada por el
 * participante de completado) y despues <em>todos</em> sus cargos en orden ascendente de ID, carga
 * conceptos/movimientos y reconstruye el contexto verificado. La frescura se comprueba con el
 * token recalculado; el cliente nunca elige el cargo por conveniencia ni aporta importes fuente.
 *
 * <p><strong>Escritura:</strong> solo OWNER/RECEPTIONIST sobre COMPLETED desde el servicio interno
 * de cobro. Crea como maximo un cargo y un pago nuevos; el sobrepago, la ambiguedad, el token
 * obsoleto y el snapshot economico incompleto se rechazan sin escribir.
 */
@Service
public class ContextualCheckoutFinancialParticipant {

    private static final int BUSINESS_SCALE = 2;

    private final ChargeRepository charges;
    private final FinancialMovementRepository movements;
    private final FinancialContextAssembler assembler;
    private final FinancialContextTokenBuilder tokens;
    private final FinancialAuthorizationService authorization;
    private final FinancialTransactionParticipant transactions;
    private final AppointmentCompletionParticipant completion;

    public ContextualCheckoutFinancialParticipant(ChargeRepository charges,
            FinancialMovementRepository movements, FinancialContextAssembler assembler,
            FinancialContextTokenBuilder tokens, FinancialAuthorizationService authorization,
            FinancialTransactionParticipant transactions,
            AppointmentCompletionParticipant completion) {
        this.charges = charges;
        this.movements = movements;
        this.assembler = assembler;
        this.tokens = tokens;
        this.authorization = authorization;
        this.transactions = transactions;
        this.completion = completion;
    }

    /**
     * Bloquea y verifica el conjunto financiero de la cita. No escribe ni consume idempotencia.
     *
     * @throws FinancialContextUnavailableException si el conjunto no puede verificarse completo
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedContext lockAndVerify(CheckoutAppointment appointment, AuthenticatedUser actor) {
        var verified = loadVerified(appointment, actor);
        return new LockedContext(verified.appointment(), verified.token());
    }

    private VerifiedContext loadVerified(CheckoutAppointment appointment, AuthenticatedUser actor) {
        return loadVerified(appointment, actor, false);
    }

    private VerifiedContext loadVerified(CheckoutAppointment appointment, AuthenticatedUser actor,
            boolean collection) {
        FinancialTransactionGuard.requireWritable();
        Objects.requireNonNull(appointment, "appointment es obligatorio");
        appointment = collection ? completion.lockForCollection(appointment.id(), actor)
                : completion.lockForCheckout(appointment.id(), actor);
        List<Long> ids = charges.findIdsByAppointmentId(appointment.id());
        List<Charge> locked = new ArrayList<>(ids.size());
        for (Long id : ids) {
            // Orden determinista por ID, ya devuelto por la consulta; se bloquea cada fila antes de
            // leer conceptos/movimientos para que la foto sea estable frente a pagos/anulaciones.
            locked.add(charges.findByIdForUpdate(id).orElseThrow(NotFoundException::new));
        }
        List<FinancialContextCharge> context = assembler.verifyAll(appointment.id(),
                appointment.patientId(), locked, actor);
        String token = tokens.tokenFor(stateOf(appointment), context);
        return new VerifiedContext(appointment, List.copyOf(context), token);
    }

    /** Retired integrated-checkout writer: a legacy guard can never reactivate it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Execution executeNew(LockedContext locked, ChargeInstruction chargeInstruction,
            PaymentInstruction paymentInstruction, AuthenticatedUser actor, Instant now) {
        throw new com.dentalclinic.identity.domain.exception.ForbiddenOperationException(
                "El checkout integrado esta retirado. Usa /collection.");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    LockedContext lockAndVerifyCollection(Long appointmentId, AuthenticatedUser actor) {
        var appointment = completion.lockForCollection(appointmentId, actor);
        var verified = loadVerified(appointment, actor, true);
        return new LockedContext(verified.appointment(), verified.token());
    }

    /** Administrative, COMPLETED-only writer; never completes or otherwise changes an appointment. */
    @Transactional(propagation = Propagation.MANDATORY)
    Execution executeCollection(LockedContext locked, ChargeInstruction chargeInstruction,
            PaymentInstruction paymentInstruction, AuthenticatedUser actor, Instant now) {
        AppointmentCompletionParticipant.requireCollectionRole(actor);
        Objects.requireNonNull(locked, "locked es obligatorio");
        Objects.requireNonNull(chargeInstruction, "chargeInstruction es obligatorio");
        Objects.requireNonNull(paymentInstruction, "paymentInstruction es obligatorio");
        Objects.requireNonNull(actor, "actor es obligatorio");
        Objects.requireNonNull(now, "now es obligatorio");
        FinancialTransactionGuard.requireWritable();
        // Never trust an application DTO as authorization or as a persisted economic snapshot.
        var verified = loadVerified(locked.appointment(), actor, true);
        CheckoutAppointment appointment = verified.appointment();
        if (appointment.status() != com.dentalclinic.appointment.domain.AppointmentStatus.COMPLETED
                || chargeInstruction.action() == null || chargeInstruction.expectedTotal() == null
                || paymentInstruction.action() == null) {
            throw new IllegalStateException("Precondiciones internas de cobro invalidas.");
        }
        String currentToken = verified.token();
        if (!currentToken.equals(locked.token())) {
            throw new FinancialConflictException("El contexto financiero cambio desde la lectura.");
        }
        if (!locked.token().equals(chargeInstruction.financialContextToken())) {
            throw new FinancialConflictException("El contexto financiero cambio desde la lectura.");
        }

        List<Charge> active = verified.charges().stream()
                .map(FinancialContextCharge::charge)
                .filter(charge -> !charge.isCancelled())
                .toList();
        if (active.size() > 1) {
            throw new CheckoutChargeAmbiguousException();
        }

        Charge charge;
        Disposition disposition;
        ChargeFinancialState beforePayment;
        if (chargeInstruction.action() == ChargeAction.CREATE_FROM_APPOINTMENT) {
            if (!active.isEmpty()) {
                throw new FinancialConflictException(
                        "La cita ya tiene un cargo activo; debes reutilizarlo.");
            }
            requireUsableSnapshot(appointment, chargeInstruction.expectedTotal());
            charge = transactions.createConfirmed(new FinancialTransactionParticipant.ChargeDraft(
                    appointment.patientId(), appointment.id(),
                    List.of(new ChargeItemData(appointment.serviceId(), appointment.dentistId(),
                            appointment.serviceName(), 1, appointment.serviceReferencePrice())),
                    null), actor, now);
            disposition = Disposition.CREATED;
            beforePayment = ChargeStatusCalculator.evaluate(charge.getTotalAmount(), BigDecimal.ZERO,
                    false);
        } else {
            if (active.isEmpty()) {
                throw new FinancialConflictException(
                        "El cargo seleccionado ya no esta activo; vuelve a leer el contexto.");
            }
            Charge selected = active.get(0);
            if (!selected.getId().equals(chargeInstruction.chargeId())) {
                throw new FinancialConflictException(
                        "El cargo seleccionado no coincide con el contexto verificado.");
            }
            if (selected.getTotalAmount().compareTo(chargeInstruction.expectedTotal()) != 0) {
                throw new FinancialConflictException("El total confirmado no coincide con el cargo.");
            }
            charge = transactions.lockCharge(selected.getId());
            if (charge.isCancelled()) {
                throw new FinancialConflictException(
                        "El cargo seleccionado ya no esta activo; vuelve a leer el contexto.");
            }
            disposition = Disposition.REUSED;
            beforePayment = stateOf(charge);
        }

        FinancialMovement payment = null;
        BigDecimal appliedAfter = beforePayment.appliedAmount();
        if (paymentInstruction.action() == PaymentAction.REGISTER) {
            BigDecimal amount = paymentInstruction.amount();
            if (amount == null || amount.signum() <= 0) {
                throw new FinancialConflictException("El pago debe ser positivo.");
            }
            if (!represented(amount)) {
                throw new FinancialConflictException("El importe del pago no es representable.");
            }
            if (amount.compareTo(beforePayment.pendingBalance()) > 0) {
                throw new CheckoutPaymentExceedsBalanceException();
            }
            payment = transactions.registerPayment(charge, paymentInstruction.method(), amount,
                    null, actor.userId(), now);
            appliedAfter = appliedAfter.add(amount);
        } else if (paymentInstruction.method() != null || paymentInstruction.amount() != null) {
            throw new FinancialConflictException(
                    "NO_NEW_PAYMENT no admite metodo ni importe.");
        }

        ChargeFinancialState after = ChargeStatusCalculator.evaluate(charge.getTotalAmount(),
                appliedAfter, false);
        return new Execution(disposition, charge.getId(), charge.getPatientId(),
                charge.getAppointmentId(), charge.getTotalAmount(), charge.getCurrency(),
                after.appliedAmount(), after.pendingBalance(), after.status(),
                payment == null ? null : payment.getId(),
                payment == null ? null : payment.getAmount(),
                payment == null ? null : payment.getPaymentMethod());
    }

    /**
     * Comprueba que un replay sigue siendo legible por el actor actual y que las referencias del
     * snapshot coinciden con las filas persistidas. No exige que el cargo siga pendiente ni que la
     * cita siga {@code SCHEDULED}: el cierre ya ocurrio.
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public void verifyReplayReferences(AuthenticatedUser actor, Long appointmentId, Long patientId,
            Long chargeId, Long paymentId) {
        Charge charge = charges.findById(chargeId)
                .orElseThrow(() -> new IllegalStateException("Falta cargo del cierre durable."));
        if (!appointmentId.equals(charge.getAppointmentId())
                || !patientId.equals(charge.getPatientId())) {
            throw new IllegalStateException("Referencias financieras del replay incompatibles.");
        }
        authorization.requireView(actor, charge);
        if (paymentId != null) {
            FinancialMovement payment = movements.findById(paymentId)
                    .orElseThrow(() -> new IllegalStateException("Falta pago del cierre durable."));
            if (!chargeId.equals(payment.getChargeId())
                    || !patientId.equals(payment.getPatientId())
                    || payment.getType() != com.dentalclinic.financial.domain.FinancialMovementType.PAYMENT) {
                throw new IllegalStateException("Referencias del pago del replay incompatibles.");
            }
        }
    }

    private ChargeFinancialState stateOf(Charge charge) {
        BigDecimal applied = movements.sumValidAppliedAmount(charge.getId()).orElse(BigDecimal.ZERO);
        return ChargeStatusCalculator.evaluate(charge.getTotalAmount(), applied,
                charge.isCancelled());
    }

    private static void requireUsableSnapshot(CheckoutAppointment appointment,
            BigDecimal expectedTotal) {
        if (appointment.serviceId() == null || appointment.serviceName() == null
                || appointment.serviceName().isBlank()
                || appointment.serviceReferencePrice() == null
                || appointment.serviceDurationMinutes() == null
                || appointment.serviceDurationMinutes() <= 0) {
            throw new CheckoutEconomicReferenceRequiredException();
        }
        BigDecimal price = appointment.serviceReferencePrice();
        if (price.signum() <= 0) {
            throw new CheckoutEconomicReferenceRequiredException();
        }
        if (!represented(price)) {
            throw new CheckoutEconomicReferenceRequiredException();
        }
        if (price.compareTo(expectedTotal) != 0) {
            throw new FinancialConflictException(
                    "El total confirmado no coincide con el snapshot de la cita.");
        }
    }

    private static boolean represented(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            return false;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() > BUSINESS_SCALE) {
            return false;
        }
        int integerDigits = stripped.precision() - stripped.scale();
        return integerDigits <= 15;
    }

    private static AppointmentState stateOf(CheckoutAppointment appointment) {
        return new AppointmentState(appointment.id(), appointment.patientId(),
                appointment.dentistId(), appointment.status(), appointment.occurrenceVersion(),
                appointment.startAt(), appointment.endAt(), appointment.durationMinutes(),
                appointment.serviceId(), appointment.serviceName(), appointment.serviceDescription(),
                appointment.serviceReferencePrice(), appointment.serviceDurationMinutes());
    }

    public enum ChargeAction {
        CREATE_FROM_APPOINTMENT,
        REUSE
    }

    public enum PaymentAction {
        REGISTER,
        NO_NEW_PAYMENT
    }

    public enum Disposition {
        CREATED,
        REUSED
    }

    /** Instruccion de cargo ya validada sintacticamente por el endpoint. */
    public record ChargeInstruction(ChargeAction action, Long chargeId, BigDecimal expectedTotal,
            String financialContextToken) {
    }

    /** Instruccion de pago; {@code NO_NEW_PAYMENT} exige metodo e importe nulos. */
    public record PaymentInstruction(PaymentAction action, PaymentMethod method, BigDecimal amount) {
    }

    /** Contexto bloqueado y verificado de la cita. */
    public record LockedContext(CheckoutAppointment appointment, String token) {
    }

    private record VerifiedContext(CheckoutAppointment appointment,
            List<FinancialContextCharge> charges, String token) {
    }

    /** Resultado financiero de una ocurrencia nueva, antes de persistir el cierre durable. */
    public record Execution(Disposition chargeDisposition, Long chargeId, Long patientId,
            Long appointmentId, BigDecimal totalAmount, String currency, BigDecimal appliedAmount,
            BigDecimal pendingBalance, ChargeStatus status, Long paymentId, BigDecimal paymentAmount,
            PaymentMethod paymentMethod) {
    }
}

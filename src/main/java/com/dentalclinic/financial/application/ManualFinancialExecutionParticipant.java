package com.dentalclinic.financial.application;

import java.time.Clock;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.appointment.application.AppointmentCheckoutReferenceReader;
import com.dentalclinic.financial.application.command.CancelChargeCommand;
import com.dentalclinic.financial.application.command.ConfirmChargeCommand;
import com.dentalclinic.financial.application.command.RegisterPaymentCommand;
import com.dentalclinic.financial.application.command.ReversePaymentCommand;
import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.FinancialMovement;
import com.dentalclinic.financial.domain.FinancialIntentOperation;
import com.dentalclinic.financial.domain.FinancialIntent;
import com.dentalclinic.financial.infrastructure.repository.ChargeRepository;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/**
 * Ejecucion comun B2 de las cuatro operaciones manuales, autorizada e idempotente.
 *
 * <p>Las fachadas conservan sus contratos y su frontera transaccional. La validacion y
 * persistencia reutilizables viven en {@link FinancialTransactionParticipant} y la idempotencia
 * HTTP en {@link FinancialIdempotencyStore}; ambos son componentes internos con propagacion
 * {@link Propagation#MANDATORY}. El commit pertenece a FinancialOperationsService o al ejecutor
 * de intenciones, nunca a este participante. No encadenar fachadas REQUIRES_NEW.
 *
 * <p>Orden de bloqueos: clave (pg_advisory_xact_lock), intencion si existe, cita antes del alta asociada o fila del cargo
 * para pagos/reversas/anulaciones. Nunca se toma cita despues de cargo. No se bloquea SUM.
 * READ_COMMITTED permite que quien espera vea el commit
 * anterior. REQUIRES_NEW evita heredar snapshots/entidades obsoletas o transacciones read-only
 * de un llamador; cada operacion tiene su propia frontera atomica, incluso en replay.
 *
 * <p>Se reutilizan entidad/repositorio/UNIQUE de http_idempotency, sin alterar citas. El bloqueo
 * asesor resuelve tambien el caso de una clave que aun no tiene fila. Colisiones de su hash de
 * 64 bits solo serializan claves distintas: la identidad real y el hash SHA-256 de solicitud
 * siempre se comprueban por separado. Los locks se liberan automaticamente en commit/rollback.
 *
 * <p>La respuesta original se conserva en JSONB (version 1). Decimales como cadenas evitan
 * conversion a Double al deserializar Map. El mapper privado usa campos para reconstruir valores
 * detached sin setters ni campos calculados; no modifica el mapper global ni persiste el replay.
 * Este formato interno no es el contrato HTTP de Fase 3.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class ManualFinancialExecutionParticipant {

    private static final String CONFIRM = "FIN_CONFIRM_CHARGE";
    private static final String PAYMENT = "FIN_REGISTER_PAYMENT";
    private static final String REVERSE = "FIN_REVERSE_PAYMENT";
    private static final String CANCEL = "FIN_CANCEL_CHARGE";

    private final ChargeRepository charges;
    private final FinancialAuthorizationService authorization;
    private final FinancialTransactionParticipant participant;
    private final FinancialIdempotencyStore idempotency;
    private final AppointmentCheckoutReferenceReader appointments;
    private final Clock clock;
    private final FinancialIntentExecutionGuard intents;

    ManualFinancialExecutionParticipant(ChargeRepository charges, FinancialAuthorizationService authorization,
            FinancialTransactionParticipant participant, FinancialIdempotencyStore idempotency,
            AppointmentCheckoutReferenceReader appointments, Clock clock, FinancialIntentExecutionGuard intents) {
        this.charges = charges;
        this.authorization = authorization;
        this.participant = participant;
        this.idempotency = idempotency;
        this.appointments = appointments;
        this.clock = clock;
        this.intents = intents;
    }

    /**
     * Solo devuelve un recibo confirmado de la identidad ya bloqueada del actor.
     * Permite recuperar tambien B1 con representacion decimal perdida, SIN reconstruir
     * un comando ni llamar factories de negocio. Reautoriza sobre el recurso actual;
     * devuelve el snapshot historico, no lo sustituye por el estado actual.
     */
    public <T> T replayRegistered(FinancialIntent intent, AuthenticatedUser actor, Class<T> resultType) {
        FinancialTransactionGuard.requireWritable();
        requireActor(actor);
        if (!actor.userId().equals(intent.actorUserId())) {
            throw new NotFoundException();
        }
        FinancialIntentExecutionGuard.requireOpen(intent);
        FinancialIntentExecutionGuard.requireSupportedVersion(intent);
        authorization.requireIntentRegistration(actor, intent.operation());
        T replay = idempotency.registeredReplay(actor, intent.operation().name(), intent.idempotencyKey(),
                intent.requestHash(), resultType);
        if (replay == null) {
            return null;
        }
        switch (intent.operation()) {
            case FIN_CONFIRM_CHARGE -> {
                Charge charge = (Charge) replay;
                authorization.requireView(actor, charges.findById(charge.getId()).orElseThrow(NotFoundException::new));
            }
            case FIN_REGISTER_PAYMENT -> {
                FinancialMovement movement = (FinancialMovement) replay;
                authorization.requireRegisterPayment(actor, participant.lockCharge(movement.getChargeId()));
            }
            case FIN_REVERSE_PAYMENT -> {
                authorization.requireReverse(actor);
                participant.lockCharge(((FinancialMovement) replay).getChargeId());
            }
            case FIN_CANCEL_CHARGE -> {
                authorization.requireCancel(actor);
                participant.lockCharge(((Charge) replay).getId());
            }
        }
        return replay;
    }

    public Charge confirmCharge(ConfirmChargeCommand command, AuthenticatedUser actor) {
        requireActor(actor);
        Objects.requireNonNull(command, "command es obligatorio");
        authorization.requireCreateCharge(actor, command);
        String hash = idempotency.lockRequest(actor, FinancialIdempotencyStore.FINANCIAL_NAMESPACE,
                CONFIRM, command.idempotencyKey(), command);
        intents.checkIfRegistered(actor, FinancialIntentOperation.FIN_CONFIRM_CHARGE, command.idempotencyKey(), hash);
        Charge replay = idempotency.replay(actor, CONFIRM, command.idempotencyKey(), hash, Charge.class);
        if (replay != null) {
            authorization.requireView(actor, charges.findById(replay.getId()).orElseThrow(NotFoundException::new));
            return replay;
        }
        if (command.appointmentId() != null) {
            // Protocolo comun de locks: clave HTTP, cita y despues el alta del cargo. Bloquear la
            // cita impide que un checkout concurrente cree desde snapshot mientras este alta
            // anade otro cargo activo, y viceversa.
            var appointment = appointments.lockById(command.appointmentId())
                    .orElseThrow(NotFoundException::new);
            if (!Objects.equals(appointment.patientId(), command.patientId())) {
                throw new com.dentalclinic.financial.domain.InvalidChargeException(
                        "La cita asociada no pertenece al paciente del cargo.");
            }
        }
        Charge charge = participant.createConfirmed(new FinancialTransactionParticipant.ChargeDraft(
                command.patientId(), command.appointmentId(), command.items(), command.notes()),
                actor, clock.instant());
        return idempotency.remember(actor, CONFIRM, command.idempotencyKey(), hash, 201, charge, Charge.class);
    }

    public FinancialMovement registerPayment(RegisterPaymentCommand command, AuthenticatedUser actor) {
        requireActor(actor);
        Objects.requireNonNull(command, "command es obligatorio");
        String hash = idempotency.lockRequest(actor, FinancialIdempotencyStore.FINANCIAL_NAMESPACE,
                PAYMENT, command.idempotencyKey(), command);
        intents.checkIfRegistered(actor, FinancialIntentOperation.FIN_REGISTER_PAYMENT, command.idempotencyKey(), hash);
        Charge charge = participant.lockCharge(command.chargeId());
        authorization.requireRegisterPayment(actor, charge);
        FinancialMovement replay = idempotency.replay(actor, PAYMENT, command.idempotencyKey(), hash,
                FinancialMovement.class);
        if (replay != null) {
            return replay;
        }
        FinancialMovement payment = participant.registerPayment(charge, command.method(),
                command.amount(), command.reason(), actor.userId(), clock.instant());
        return idempotency.remember(actor, PAYMENT, command.idempotencyKey(), hash, 201, payment,
                FinancialMovement.class);
    }

    public FinancialMovement reversePayment(ReversePaymentCommand command, AuthenticatedUser actor) {
        requireActor(actor);
        Objects.requireNonNull(command, "command es obligatorio");
        authorization.requireReverse(actor);
        String hash = idempotency.lockRequest(actor, FinancialIdempotencyStore.FINANCIAL_NAMESPACE,
                REVERSE, command.idempotencyKey(), command);
        intents.checkIfRegistered(actor, FinancialIntentOperation.FIN_REVERSE_PAYMENT, command.idempotencyKey(), hash);
        Charge charge = participant.lockCharge(command.chargeId());
        FinancialMovement replay = idempotency.replay(actor, REVERSE, command.idempotencyKey(), hash,
                FinancialMovement.class);
        if (replay != null) {
            return replay;
        }
        FinancialMovement reversal = participant.reversePayment(charge, command.movementId(),
                command.reason(), actor.userId(), clock.instant());
        return idempotency.remember(actor, REVERSE, command.idempotencyKey(), hash, 201, reversal,
                FinancialMovement.class);
    }

    public Charge cancelCharge(CancelChargeCommand command, AuthenticatedUser actor) {
        requireActor(actor);
        Objects.requireNonNull(command, "command es obligatorio");
        authorization.requireCancel(actor);
        String hash = idempotency.lockRequest(actor, FinancialIdempotencyStore.FINANCIAL_NAMESPACE,
                CANCEL, command.idempotencyKey(), command);
        intents.checkIfRegistered(actor, FinancialIntentOperation.FIN_CANCEL_CHARGE, command.idempotencyKey(), hash);
        Charge charge = participant.lockCharge(command.chargeId());
        Charge replay = idempotency.replay(actor, CANCEL, command.idempotencyKey(), hash, Charge.class);
        if (replay != null) {
            return replay;
        }
        Charge cancelled = participant.cancelCharge(charge, command.reason(), actor.userId(),
                clock.instant());
        return idempotency.remember(actor, CANCEL, command.idempotencyKey(), hash, 200, cancelled,
                Charge.class);
    }

    private static void requireActor(AuthenticatedUser actor) {
        if (actor == null || actor.role() == null || actor.userId() == null || actor.userId() <= 0) {
            throw new ForbiddenOperationException("No hay identidad autenticada con rol.");
        }
    }
}

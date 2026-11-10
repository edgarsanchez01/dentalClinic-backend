package com.dentalclinic.financial.application;

import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.financial.application.command.CancelChargeCommand;
import com.dentalclinic.financial.application.command.ConfirmChargeCommand;
import com.dentalclinic.financial.application.command.RegisterPaymentCommand;
import com.dentalclinic.financial.application.command.ReversePaymentCommand;
import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.FinancialIntentCommandConflictException;
import com.dentalclinic.financial.domain.FinancialIntentCommandUnavailableException;
import com.dentalclinic.financial.domain.FinancialIntentOperation;
import com.dentalclinic.financial.domain.FinancialMovement;
import com.dentalclinic.financial.infrastructure.repository.FinancialIntentRepository;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/**
 * Transaccion de ejecucion B2/B3. Sin payload alternativo ni actorId del cliente:
 * carga el comando durable de la identidad del actor autenticado. No registra,
 * cierra ni reintenta automaticamente. Efecto y recibo comparten esta transaccion.
 */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
class FinancialIntentExecutionTransaction {
    private final FinancialIdempotencyStore idempotency;
    private final FinancialIntentExecutionGuard intents;
    private final FinancialAuthorizationService authorization;
    private final ManualFinancialExecutionParticipant execution;
    private final FinancialIntentRepository repository;

    FinancialIntentExecutionTransaction(FinancialIdempotencyStore idempotency, FinancialIntentExecutionGuard intents,
            FinancialAuthorizationService authorization, ManualFinancialExecutionParticipant execution,
            FinancialIntentRepository repository) {
        this.idempotency = idempotency;
        this.intents = intents;
        this.authorization = authorization;
        this.execution = execution;
        this.repository = repository;
    }

    public Charge confirmCharge(String intentId, AuthenticatedUser actor, FinancialIntentExecutionAttempt attempt) {
        return execute(actor, FinancialIntentOperation.FIN_CONFIRM_CHARGE, intentId,
                ConfirmChargeCommand.class, ConfirmChargeCommand::idempotencyKey, Charge.class,
                command -> execution.confirmCharge(command, actor), attempt);
    }

    public FinancialMovement registerPayment(String intentId, AuthenticatedUser actor, FinancialIntentExecutionAttempt attempt) {
        return execute(actor, FinancialIntentOperation.FIN_REGISTER_PAYMENT, intentId,
                RegisterPaymentCommand.class, RegisterPaymentCommand::idempotencyKey, FinancialMovement.class,
                command -> execution.registerPayment(command, actor), attempt);
    }

    public FinancialMovement reversePayment(String intentId, AuthenticatedUser actor, FinancialIntentExecutionAttempt attempt) {
        return execute(actor, FinancialIntentOperation.FIN_REVERSE_PAYMENT, intentId,
                ReversePaymentCommand.class, ReversePaymentCommand::idempotencyKey, FinancialMovement.class,
                command -> execution.reversePayment(command, actor), attempt);
    }

    public Charge cancelCharge(String intentId, AuthenticatedUser actor, FinancialIntentExecutionAttempt attempt) {
        return execute(actor, FinancialIntentOperation.FIN_CANCEL_CHARGE, intentId,
                CancelChargeCommand.class, CancelChargeCommand::idempotencyKey, Charge.class,
                command -> execution.cancelCharge(command, actor), attempt);
    }

    private <C, R> R execute(AuthenticatedUser actor, FinancialIntentOperation operation, String key,
            Class<C> type, Function<C, String> keyOf, Class<R> resultType, Function<C, R> execute,
            FinancialIntentExecutionAttempt attempt) {
        authorization.requireIntentRegistration(actor, operation);
        FinancialIntentRegistrationService.requireCanonicalKey(key);
        idempotency.lockIdentity(actor, FinancialIdempotencyStore.FINANCIAL_NAMESPACE, operation.name(), key);
        var intent = intents.requireRegistered(actor, operation, key);
        FinancialIntentExecutionGuard.requireOpen(intent);
        // Versiones antes de deserializar. Recibo confirmado es la autoridad de exito,
        // incluso si JSONB ya perdio la representacion numerica del comando B1.
        FinancialIntentExecutionGuard.requireSupportedVersion(intent);
        R replay = execution.replayRegistered(intent, actor, resultType);
        if (replay != null) {
            return replay;
        }
        C command = idempotency.readCommand(intent.commandBody(), type);
        if (command == null || !key.equals(keyOf.apply(command))) {
            throw new FinancialIntentCommandConflictException();
        }
        String hash = idempotency.requestHash(command);
        if (intent.commandVersion() == 1 && !intent.requestHash().equals(hash)) {
            // No adivinar escala/exponente, ni rehash/backfill, ni falsear REJECTED.
            throw new FinancialIntentCommandUnavailableException();
        }
        FinancialIntentExecutionGuard.requireCompatible(intent, hash);
        attempt.begin(intent, repository.databaseTime());
        try {
            return execute.apply(command);
        } catch (RuntimeException failure) {
            attempt.failed(failure);
            throw failure;
        }
    }
}

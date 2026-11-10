package com.dentalclinic.financial.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.financial.domain.FinancialIntent;
import com.dentalclinic.financial.domain.FinancialIntentClosedException;
import com.dentalclinic.financial.domain.FinancialIntentClosureBlockedException;
import com.dentalclinic.financial.domain.FinancialIntentClosureUncertainException;
import com.dentalclinic.financial.domain.FinancialIntentDisposition;
import com.dentalclinic.financial.domain.FinancialIntentOperation;
import com.dentalclinic.financial.infrastructure.repository.FinancialIntentRepository;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/**
 * Cierres internos B3; commit independiente y sin escrituras de negocio/recibo.
 * Identidad -> fila de intencion -> consulta de recibo, bajo READ_COMMITTED.
 * Esperar el advisory lock prueba que ningun escritor cooperante sigue ejecutando
 * esa identidad. No basta una lectura suelta de ausencia de recibo.
 */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
public class FinancialIntentClosureService {
    private final FinancialIdempotencyStore idempotency;
    private final FinancialIntentExecutionGuard intents;
    private final FinancialIntentRepository repository;
    private final FinancialAuthorizationService authorization;

    FinancialIntentClosureService(FinancialIdempotencyStore idempotency, FinancialIntentExecutionGuard intents,
            FinancialIntentRepository repository, FinancialAuthorizationService authorization) {
        this.idempotency = idempotency;
        this.intents = intents;
        this.repository = repository;
        this.authorization = authorization;
    }

    public FinancialIntent abandon(FinancialIntentOperation operation, String intentId, AuthenticatedUser actor) {
        FinancialIntent intent = lockAndCheck(operation, intentId, actor);
        if (intent.disposition() == FinancialIntentDisposition.ABANDONED) return intent;
        FinancialIntentExecutionGuard.requireOpen(intent);
        if (!repository.closeOpen(intent, FinancialIntentDisposition.ABANDONED, null, null, null)) {
            throw new FinancialIntentClosureUncertainException();
        }
        return intents.requireRegistered(actor, operation, intentId);
    }

    /** No es una API de rechazo arbitrario: solo el coordinador puede aportar esta evidencia interna. */
    public FinancialIntent rejectAfterRollback(FinancialIntentExecutionAttempt attempt, RuntimeException failure) {
        if (attempt == null || !attempt.provesRollbackFor(failure)) {
            throw new IllegalStateException("Rollback financiero no acreditado.");
        }
        FinancialIntent intent = lockAndCheck(attempt.operation, attempt.key, attempt.actor);
        FinancialIntentExecutionGuard.requireCompatible(intent, attempt.hash());
        if (intent.disposition() == FinancialIntentDisposition.REJECTED) return intent;
        if (intent.disposition() == FinancialIntentDisposition.ABANDONED) throw new FinancialIntentClosedException();
        if (!repository.closeOpen(intent, FinancialIntentDisposition.REJECTED, attempt.attemptedAt(),
                attempt.reason().name(), attempt.reason().httpStatus())) {
            throw new FinancialIntentClosureUncertainException();
        }
        return intents.requireRegistered(attempt.actor, attempt.operation, attempt.key);
    }

    private FinancialIntent lockAndCheck(FinancialIntentOperation operation, String key, AuthenticatedUser actor) {
        authorization.requireIntentRegistration(actor, operation);
        FinancialIntentRegistrationService.requireCanonicalKey(key);
        idempotency.lockIdentity(actor, FinancialIdempotencyStore.FINANCIAL_NAMESPACE, operation.name(), key);
        FinancialIntent intent = intents.requireRegistered(actor, operation, key);
        FinancialIntentExecutionGuard.requireSupportedVersion(intent);
        if (idempotency.hasReceipt(actor, operation.name(), key)) {
            throw new FinancialIntentClosureBlockedException();
        }
        return intent;
    }
}

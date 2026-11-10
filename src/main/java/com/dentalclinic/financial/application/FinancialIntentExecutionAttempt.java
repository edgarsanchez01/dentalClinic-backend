package com.dentalclinic.financial.application;

import java.time.Instant;
import java.util.Objects;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.dentalclinic.financial.domain.DefinitiveFinancialConflictException;
import com.dentalclinic.financial.domain.DefinitiveInvalidChargeException;
import com.dentalclinic.financial.domain.FinancialIntent;
import com.dentalclinic.financial.domain.FinancialIntentOperation;
import com.dentalclinic.financial.domain.FinancialRejectionReason;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/**
 * Evidencia efimera por invocacion, nunca DTO ni argumento fabricado por un cliente.
 * Solo afterCompletion(ROLLED_BACK), la misma excepcion tipificada y la identidad/hash
 * verificados autorizan intentar finalizar. UNKNOWN, commit/rollback fallidos o un
 * wrapper tecnico NO son prueba de ausencia de efecto.
 */
final class FinancialIntentExecutionAttempt {
    final AuthenticatedUser actor;
    final FinancialIntentOperation operation;
    final String key;
    private String hash;
    private Instant attemptedAt;
    private RuntimeException businessFailure;
    private FinancialRejectionReason reason;
    private volatile int completion = TransactionSynchronization.STATUS_UNKNOWN;

    FinancialIntentExecutionAttempt(AuthenticatedUser actor, FinancialIntentOperation operation, String key) {
        this.actor = actor;
        this.operation = operation;
        this.key = key;
    }

    void begin(FinancialIntent intent, Instant databaseTime) {
        if (hash != null || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || !Objects.equals(actor.userId(), intent.actorUserId()) || operation != intent.operation()
                || !Objects.equals(key, intent.idempotencyKey()) || databaseTime == null) {
            throw new IllegalStateException("No se pudo acreditar la transaccion del intento financiero.");
        }
        hash = intent.requestHash();
        attemptedAt = databaseTime;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) { completion = status; }
        });
    }

    void failed(RuntimeException failure) {
        FinancialRejectionReason candidate = switch (failure) {
            case DefinitiveFinancialConflictException conflict -> conflict.reason();
            case DefinitiveInvalidChargeException invalid -> invalid.reason();
            default -> null;
        };
        if (candidate != null && candidate.operation() == operation && failure.getCause() == null
                && failure.getSuppressed().length == 0) {
            reason = candidate;
            businessFailure = failure;
        }
    }

    boolean capturedBusinessFailure(RuntimeException failure) {
        return hash != null && reason != null && businessFailure == failure
                && failure.getCause() == null && failure.getSuppressed().length == 0;
    }

    boolean provesRollbackFor(RuntimeException failure) {
        return capturedBusinessFailure(failure) && completion == TransactionSynchronization.STATUS_ROLLED_BACK;
    }

    String hash() { return hash; }
    Instant attemptedAt() { return attemptedAt; }
    FinancialRejectionReason reason() { return reason; }
}

package com.dentalclinic.financial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.dentalclinic.financial.domain.DefinitiveFinancialConflictException;
import com.dentalclinic.financial.domain.FinancialConflictException;
import com.dentalclinic.financial.domain.FinancialIntent;
import com.dentalclinic.financial.domain.FinancialIntentDisposition;
import com.dentalclinic.financial.domain.FinancialIntentOperation;
import com.dentalclinic.financial.domain.FinancialRejectionReason;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

class FinancialIntentExecutionAttemptTest {
    private final AuthenticatedUser owner = new AuthenticatedUser(1L, Role.OWNER);
    private final FinancialIntentOperation operation = FinancialIntentOperation.FIN_REGISTER_PAYMENT;

    @ParameterizedTest
    @ValueSource(ints = {TransactionSynchronization.STATUS_COMMITTED, TransactionSynchronization.STATUS_ROLLED_BACK, TransactionSynchronization.STATUS_UNKNOWN})
    void soloElMismoErrorYRollbackAcreditadoAutorizanCierre(int completion) {
        var attempt = new FinancialIntentExecutionAttempt(owner, operation, "key");
        var failure = new DefinitiveFinancialConflictException(FinancialRejectionReason.PAYMENT_EXCEEDS_BALANCE, "saldo");
        try {
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            attempt.begin(intent(), Instant.now());
            attempt.failed(failure);
            assertThat(attempt.provesRollbackFor(failure)).isFalse();
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(completion));
            assertThat(attempt.provesRollbackFor(failure)).isEqualTo(completion == TransactionSynchronization.STATUS_ROLLED_BACK);
            assertThat(attempt.provesRollbackFor(new IllegalStateException("rollback incierto", failure))).isFalse();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void errorGenericoOMensajeOReasonDeOtraOperacionNoSonAllowlist() {
        for (RuntimeException failure : new RuntimeException[] {
                new FinancialConflictException("El pago supera el saldo pendiente del cargo."),
                new IllegalStateException("timeout"),
                new DefinitiveFinancialConflictException(FinancialRejectionReason.PAYMENT_ALREADY_REVERSED, "otra operacion")}) {
            var attempt = new FinancialIntentExecutionAttempt(owner, operation, "key");
            try {
                TransactionSynchronizationManager.initSynchronization();
                TransactionSynchronizationManager.setActualTransactionActive(true);
                attempt.begin(intent(), Instant.now());
                attempt.failed(failure);
                TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
                assertThat(attempt.provesRollbackFor(failure)).isFalse();
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        }
    }

    @Test
    void sinTransaccionNoSePuedeFabricarPrueba() {
        var attempt = new FinancialIntentExecutionAttempt(owner, operation, "key");
        assertThatThrownBy(() -> attempt.begin(intent(), Instant.now())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void coordinadorNoPublicaRechazoSiElRollbackTerminaUnknown() {
        var worker = org.mockito.Mockito.mock(FinancialIntentExecutionTransaction.class);
        var closure = org.mockito.Mockito.mock(FinancialIntentClosureService.class);
        var coordinator = new FinancialIntentExecutionService(worker, closure);
        var failure = new DefinitiveFinancialConflictException(FinancialRejectionReason.PAYMENT_EXCEEDS_BALANCE, "saldo");
        org.mockito.Mockito.when(worker.registerPayment(org.mockito.ArgumentMatchers.eq("key"),
                org.mockito.ArgumentMatchers.eq(owner), org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            FinancialIntentExecutionAttempt attempt = invocation.getArgument(2);
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                attempt.begin(intent(), Instant.now());
                attempt.failed(failure);
                TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN));
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
            throw failure;
        });
        assertThatThrownBy(() -> coordinator.registerPayment("key", owner))
                .isInstanceOf(com.dentalclinic.financial.domain.FinancialIntentClosureUncertainException.class);
        org.mockito.Mockito.verifyNoInteractions(closure);
    }

    private FinancialIntent intent() {
        return new FinancialIntent(1L, operation, "key", 2, 1, "a".repeat(64), "{}", FinancialIntentDisposition.OPEN,
                Instant.now(), null, null, null, null, null);
    }
}

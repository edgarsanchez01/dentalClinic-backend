package com.dentalclinic.financial.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import com.dentalclinic.financial.CollectionFlowSupport;
import com.dentalclinic.appointment.application.AppointmentCompletionParticipant;
import com.dentalclinic.appointment.application.OperationIdempotencyStore;
import com.dentalclinic.financial.infrastructure.repository.*;

/** Replaces obsolete integrated-checkout fault injection with the separated financial transaction. */
class CollectionAtomicityTest extends CollectionFlowSupport {
    @MockitoSpyBean private ChargeItemRepository items;
    @MockitoSpyBean private FinancialMovementRepository movements;
    @MockitoSpyBean private OperationIdempotencyStore idempotency;
    @Autowired private ContextualCheckoutFinancialParticipant financial;
    @Autowired private AppointmentCompletionParticipant completion;

    @ParameterizedTest @ValueSource(strings={"charge","items","payment","idempotency"})
    void failureAfterEachStageRollsBackFinancialWritesButNeverClinicalCompletion(String stage) {
        var command=collectionCommand("rollback",null,"200");
        OperationIdempotencyStore target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(idempotency);
        var before=jdbc.queryForMap("SELECT * FROM appointment WHERE id=?",scheduled);
        var failure=new IllegalStateException("injected");
        switch(stage) {
            case "charge" -> doThrow(failure).when(items).save(any());
            case "items" -> doThrow(failure).when(movements).save(any());
            case "payment" -> doThrow(failure).when(target).remember(any(),anyString(),anyString(),anyString(),any());
            default -> doAnswer(invocation -> { invocation.callRealMethod(); throw failure; })
                    .when(target).remember(any(),anyString(),anyString(),anyString(),any());
        }
        assertThatThrownBy(() -> collections.collect(command,reception)).isInstanceOf(RuntimeException.class);
        assertClinicallyCompleted();
        assertThat(jdbc.queryForMap("SELECT * FROM appointment WHERE id=?",scheduled)).isEqualTo(before);
        assertThat(count("charge")).isEqualTo(1);
        assertThat(count("charge_item")).isZero();
        assertThat(count("financial_movement")).isZero();
        assertThat(receipts()).isZero();
        reset(items,movements,target);
        assertThat(collections.collect(command,reception).financialAtCollection().chargeId()).isPositive();
    }

    @Test void participantsRequireTransactionWritableIsolationAndAdministrativeRole() {
        assertThatThrownBy(() -> financial.lockAndVerifyCollection(scheduled,reception))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        for (boolean readOnly: new boolean[]{true,false}) {
            var tx=new TransactionTemplate(transactions);
            tx.setReadOnly(readOnly);
            if(!readOnly) tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThatThrownBy(() -> tx.execute(status -> financial.lockAndVerifyCollection(scheduled,reception)))
                    .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        }
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status ->
                financial.lockAndVerifyCollection(scheduled,doctor)))
                .isInstanceOf(com.dentalclinic.identity.domain.exception.ForbiddenOperationException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status ->
                financial.executeCollection(null,null,null,doctor,clock.instant())))
                .isInstanceOf(com.dentalclinic.identity.domain.exception.ForbiddenOperationException.class);
        assertClinicallyCompleted();
    }

    @Test void realLockTimeoutReturns503AndSameCommandCanRecover() throws Exception {
        OperationIdempotencyStore target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(idempotency);
        doAnswer(invocation -> {
            jdbc.execute("SET LOCAL lock_timeout='250ms'");
            return invocation.callRealMethod();
        }).when(target).lock(any(),anyString(),anyString(),any());
        String request=body(token(),null,"200");
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var result=new TransactionTemplate(transactions).execute(status -> {
                jdbc.queryForObject("SELECT id FROM appointment WHERE id=? FOR UPDATE",Long.class,scheduled);
                var future=pool.submit(() -> postBody("timeout",request,reception).andReturn().getResponse());
                try { return future.get(10,java.util.concurrent.TimeUnit.SECONDS); }
                catch(Exception e) { throw new AssertionError(e); }
            });
            assertThat(result.getStatus()).isEqualTo(503);
            assertThat(mapper.readTree(result.getContentAsString()).path("code").asText()).isEqualTo("COLLECTION_RETRYABLE");
        }
        assertClinicallyCompleted();
        assertThat(receipts()).isZero();
        reset(target);
        postBody("timeout",request,reception).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }
}

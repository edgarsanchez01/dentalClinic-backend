package com.dentalclinic.checkout.application;

import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.dentalclinic.financial.application.CheckoutFinancialExecutionGuard;
import com.dentalclinic.checkout.infrastructure.repository.AppointmentCheckoutRepository;
import com.dentalclinic.appointment.application.AppointmentCompletionParticipant;

/**
 * Implementacion transaccional del contexto interno del checkout.
 *
 * <p>El estado se liga a la transaccion fisica mediante un recurso de
 * {@link TransactionSynchronizationManager}, no a un {@code ThreadLocal} global. Se desvincula en
 * {@code afterCompletion}, incluso en rollback. Una transaccion {@code MANDATORY} sin este contexto
 * no puede escribir dinero ni consumir la idempotencia del cierre.
 */
@Component
class TransactionalCheckoutExecutionGuard implements CheckoutFinancialExecutionGuard {

    private final AppointmentCheckoutRepository checkouts;
    private final AppointmentCompletionParticipant completion;

    TransactionalCheckoutExecutionGuard(
            AppointmentCheckoutRepository checkouts, AppointmentCompletionParticipant completion) {
        this.checkouts = checkouts;
        this.completion = completion;
    }

    private static final Object RESOURCE_KEY = new Object();

    @Override
    public void open(Long appointmentId, Long actorUserId, int occurrenceVersion) {
        requireSynchronization();
        CheckoutTransactionGuard.requireWritable();
        if (TransactionSynchronizationManager.hasResource(RESOURCE_KEY)) {
            throw new IllegalTransactionStateException(
                    "Ya hay una ejecucion de cierre abierta en esta transaccion.");
        }
        State state = new State(appointmentId, actorUserId, occurrenceVersion);
        TransactionSynchronizationManager.bindResource(RESOURCE_KEY, state);
        state.synchronization = new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                if (!state.completed || state.chargeId == null) {
                    // beforeCommit must throw a business-state RuntimeException, not Spring's
                    // TransactionException: commit-failure handling may otherwise report UNKNOWN
                    // without rolling the physical transaction back.
                    throw new IllegalStateException("Ejecucion de checkout incompleta.");
                }
                var durable = checkouts.findByAppointmentId(state.appointmentId)
                        .orElseThrow(() -> new IllegalStateException("Falta el cierre durable."));
                if (!Objects.equals(durable.getChargeId(), state.chargeId)
                        || !Objects.equals(durable.getPaymentMovementId(), state.paymentId)
                        || !Objects.equals(durable.getActorUserId(), state.actorUserId)
                        || durable.getOccurrenceVersion() != state.occurrenceVersion) {
                    throw new IllegalStateException("Referencias del cierre incompatibles.");
                }
                completion.verifyCheckoutCompleted(state.appointmentId, state.occurrenceVersion,
                        durable.getCompletedAt());
            }
            @Override
            public void afterCompletion(int status) {
                if (TransactionSynchronizationManager.hasResource(RESOURCE_KEY)) {
                    TransactionSynchronizationManager.unbindResource(RESOURCE_KEY);
                }
            }
        };
        TransactionSynchronizationManager.registerSynchronization(state.synchronization);
    }

    @Override
    public void expectFinancialResult(Long chargeId, Long paymentMovementId) {
        State state = (State) TransactionSynchronizationManager.getResource(RESOURCE_KEY);
        if (state == null || state.chargeId != null
                || !TransactionSynchronizationManager.getSynchronizations().contains(state.synchronization)) {
            throw new IllegalTransactionStateException("Resultado financiero duplicado o sin contexto.");
        }
        state.chargeId = Objects.requireNonNull(chargeId);
        state.paymentId = paymentMovementId;
    }

    @Override
    public void requireFinancialWrite(Long appointmentId, Long actorUserId, int occurrenceVersion) {
        State state = requireMatching(appointmentId, actorUserId, occurrenceVersion);
        if (state.completed) {
            throw new IllegalTransactionStateException(
                    "La ejecucion financiera ya fue marcada como cerrada.");
        }
    }

    @Override
    public void markBusinessCompleted(Long appointmentId, Long actorUserId, int occurrenceVersion) {
        State state = requireMatching(appointmentId, actorUserId, occurrenceVersion);
        state.completed = true;
    }

    @Override
    public void requireBusinessCompleted(Long appointmentId, Long actorUserId, int occurrenceVersion) {
        if (!requireMatching(appointmentId, actorUserId, occurrenceVersion).completed) {
            throw new IllegalTransactionStateException(
                    "El cierre durable aun no fue registrado en esta ejecucion.");
        }
    }

    private static State requireMatching(Long appointmentId, Long actorUserId,
            int occurrenceVersion) {
        requireSynchronization();
        Object resource = TransactionSynchronizationManager.getResource(RESOURCE_KEY);
        if (!(resource instanceof State state)) {
            throw new IllegalTransactionStateException(
                    "No hay contexto interno de checkout abierto para esta escritura.");
        }
        if (!TransactionSynchronizationManager.getSynchronizations().contains(state.synchronization)) {
            throw new IllegalTransactionStateException("El contexto pertenece a otra transaccion suspendida.");
        }
        if (!Objects.equals(state.appointmentId, appointmentId)
                || !Objects.equals(state.actorUserId, actorUserId)
                || state.occurrenceVersion != occurrenceVersion) {
            throw new IllegalTransactionStateException(
                    "El contexto interno de checkout no coincide con la operacion.");
        }
        return state;
    }

    private static void requireSynchronization() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalTransactionStateException(
                    "El checkout requiere una transaccion con sincronizacion activa.");
        }
    }

    private static final class State {
        private final Long appointmentId;
        private final Long actorUserId;
        private final int occurrenceVersion;
        private boolean completed;
        private Long chargeId;
        private Long paymentId;
        private TransactionSynchronization synchronization;

        private State(Long appointmentId, Long actorUserId, int occurrenceVersion) {
            this.appointmentId = appointmentId;
            this.actorUserId = actorUserId;
            this.occurrenceVersion = occurrenceVersion;
        }
    }
}

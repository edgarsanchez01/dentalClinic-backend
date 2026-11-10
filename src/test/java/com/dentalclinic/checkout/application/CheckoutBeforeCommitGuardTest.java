package com.dentalclinic.checkout.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.dentalclinic.appointment.application.AppointmentCompletionParticipant;
import com.dentalclinic.appointment.domain.AppointmentStatus;
import com.dentalclinic.appointment.infrastructure.repository.AppointmentRepository;
import com.dentalclinic.checkout.CheckoutFlowSupport;
import com.dentalclinic.checkout.domain.AppointmentCheckout;
import com.dentalclinic.checkout.domain.ChargeDisposition;
import com.dentalclinic.checkout.infrastructure.repository.AppointmentCheckoutRepository;
import com.dentalclinic.financial.application.CheckoutFinancialExecutionGuard;
import com.dentalclinic.financial.application.ContextualCheckoutFinancialParticipant;
import com.dentalclinic.financial.application.ContextualCheckoutFinancialParticipant.*;

/** Real participants and PostgreSQL 16; deliberately incomplete internal orchestration, no mocks. */
class CheckoutBeforeCommitGuardTest extends CheckoutFlowSupport {
    @Autowired private CheckoutFinancialExecutionGuard guard;
    @Autowired private ContextualCheckoutFinancialParticipant financial;
    @Autowired private AppointmentCompletionParticipant completion;
    @Autowired private AppointmentCheckoutRepository checkouts;
    @Autowired private AppointmentRepository appointments;

    @ParameterizedTest
    @ValueSource(strings = {"retired-financial-write", "no-financial-result"})
    void rejectsAtBeforeCommitAndRollsBackEveryEffect(String defect) {
        var bodyReachedEnd = new AtomicBoolean();
        var committed = new AtomicBoolean();
        var rolledBack = new AtomicBoolean();
        var tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        String expected = defect.equals("no-financial-result") ? "Ejecucion de checkout incompleta."
                : "El checkout integrado esta retirado. Usa /collection.";
        assertThatThrownBy(() -> tx.execute(status -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { committed.set(true); }
                @Override public void afterCompletion(int result) { rolledBack.set(result == STATUS_ROLLED_BACK); }
            });
            var appointment = completion.lockForCheckout(scheduled, doctor);
            var locked = financial.lockAndVerify(appointment, doctor);
            guard.open(scheduled, doctor.userId(), 1);
            if (!defect.equals("no-financial-result")) {
                boolean noPayment = defect.equals("unexpected-payment");
                var execution = financial.executeNew(locked,
                        new ChargeInstruction(ChargeAction.CREATE_FROM_APPOINTMENT, null, new BigDecimal("500"), locked.token()),
                        new PaymentInstruction(noPayment ? PaymentAction.NO_NEW_PAYMENT : PaymentAction.REGISTER,
                                noPayment ? null : com.dentalclinic.financial.domain.PaymentMethod.CASH,
                                noPayment ? null : new BigDecimal("200")), doctor, clock.instant());
                Instant completedAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
                if (!defect.equals("status")) completion.completeForCheckout(scheduled, doctor, 1, completedAt);
                if (!defect.equals("missing-durable")) {
                    // Bypass the registrar only to isolate the final defensive invariant. All IDs exist.
                    Long durablePayment = defect.equals("payment") ? null : execution.paymentId();
                    if (defect.equals("unexpected-payment")) durablePayment = payment(execution.chargeId(), patientId, "10");
                    checkouts.save(AppointmentCheckout.create(scheduled,
                            defect.equals("durable-occurrence") ? 2 : 1,
                            defect.equals("charge") ? chargeId : execution.chargeId(), durablePayment,
                            ChargeDisposition.CREATED, defect.equals("actor") ? ownerId : doctor.userId(),
                            defect.equals("completed-at") ? completedAt.plusSeconds(1) : completedAt));
                }
                if (defect.equals("clinical-occurrence")) {
                    var managed = appointments.findById(scheduled).orElseThrow();
                    org.springframework.test.util.ReflectionTestUtils.setField(managed, "occurrenceVersion", 2);
                    appointments.flush();
                }
            }
            if (!defect.equals("unmarked")) guard.markBusinessCompleted(scheduled, doctor.userId(), 1);
            bodyReachedEnd.set(true);
            return null;
        })).isInstanceOf(RuntimeException.class).hasMessage(expected);
        assertThat(bodyReachedEnd.get()).isEqualTo(defect.equals("no-financial-result"));
        assertThat(committed).isFalse();
        assertThat(rolledBack).isTrue();
        assertNoCheckout();
        assertThat(appointments.findById(scheduled).orElseThrow().getStatus()).isEqualTo(AppointmentStatus.SCHEDULED);
        assertThat(appointments.findById(scheduled).orElseThrow().getOccurrenceVersion()).isEqualTo(1);
        assertThat(appointments.findById(scheduled).orElseThrow().getCompletedAt()).isNull();
        assertThat(count("charge")).isEqualTo(1); // The unrelated pre-existing fixture is intact.
        for (String table : List.of("charge_item", "financial_movement", "appointment_event", "notification",
                "communication", "communication_intention", "http_idempotency", "patient_feedback")) {
            assertThat(count(table)).as(table).isZero();
        }
        // afterCompletion unbinds the guard even after rejection; identical logical request can succeed.
        assertThatThrownBy(() -> coordinator.checkout(command("after-rejected-commit", null, "200"), doctor))
                .isInstanceOf(com.dentalclinic.identity.domain.exception.ForbiddenOperationException.class);
    }
}

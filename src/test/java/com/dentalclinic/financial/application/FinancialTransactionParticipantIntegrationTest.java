package com.dentalclinic.financial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.dentalclinic.financial.FinancialOperationsIntegrationSupport;
import com.dentalclinic.financial.application.FinancialTransactionParticipant.ChargeDraft;
import com.dentalclinic.financial.application.command.ConfirmChargeCommand;
import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.FinancialMovement;
import com.dentalclinic.financial.domain.PaymentMethod;

/**
 * Contrato transaccional del nucleo financiero interno (UX-05B.1, Etapa 3.1).
 *
 * <p>Demuestra que los participantes no abren transacciones propias: sin transaccion ambiente
 * fallan por {@code MANDATORY}; dentro de una transaccion exterior solo aportan escrituras que se
 * confirman o revierten con ella. Tambien fija la frontera opuesta: la fachada publica conserva
 * {@code REQUIRES_NEW} y no se deja arrastrar por el rollback del llamador.
 */
class FinancialTransactionParticipantIntegrationTest extends FinancialOperationsIntegrationSupport {

    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

    @Autowired
    private FinancialTransactionParticipant participant;

    @Autowired
    private FinancialIdempotencyStore idempotencyStore;

    @Test
    void unParticipanteSinTransaccionFallaAntesDeEscribir() {
        assertThatThrownBy(() -> participant.createConfirmed(draft("100"), owner, NOW))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count("charge")).isZero();
        assertThat(count("charge_item")).isZero();
        assertThat(count("financial_movement")).isZero();
    }

    @Test
    void elAlmacenIdempotenteTambienExigeTransaccionAmbiente() {
        assertThatThrownBy(() -> idempotencyStore.lockRequest(owner,
                FinancialIdempotencyStore.FINANCIAL_NAMESPACE, "TEST", key(), "cuerpo"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count("http_idempotency")).isZero();
    }

    @Test
    void unaTransaccionExteriorQueHaceCommitConservaCargoYConceptos() {
        transaction().execute(status -> {
            Charge charge = participant.createConfirmed(draft("100"), owner, NOW);
            assertThat(charge.getId()).isNotNull();
            return null;
        });

        assertThat(count("charge")).isEqualTo(1);
        assertThat(count("charge_item")).isEqualTo(1);
        assertThat(count("financial_movement")).isZero();
    }

    @Test
    void unaTransaccionExteriorQueFallaRevierteCargoConceptosYPago() {
        transaction().execute(status -> {
            Charge charge = participant.createConfirmed(draft("100"), owner, NOW);
            FinancialMovement payment = participant.registerPayment(charge, PaymentMethod.CASH,
                    new BigDecimal("40"), null, owner.userId(), NOW);
            assertThat(charge.getId()).isNotNull();
            assertThat(payment.getId()).isNotNull();
            status.setRollbackOnly();
            return null;
        });

        assertThat(count("charge")).isZero();
        assertThat(count("charge_item")).isZero();
        assertThat(count("financial_movement")).isZero();
        assertThat(count("http_idempotency")).isZero();
    }

    @Test
    void unaTransaccionExteriorQueFallaRevierteCargoConceptosPagoYSnapshotIdempotente() {
        String idempotencyKey = key();
        transaction().execute(status -> {
            Charge charge = participant.createConfirmed(draft("100"), owner, NOW);
            participant.registerPayment(charge, PaymentMethod.CASH, new BigDecimal("40"), null,
                    owner.userId(), NOW);
            String hash = idempotencyStore.lockRequest(owner,
                    FinancialIdempotencyStore.FINANCIAL_NAMESPACE, "CONFIRM_CHARGE", idempotencyKey,
                    "cuerpo");
            idempotencyStore.remember(owner, "CONFIRM_CHARGE", idempotencyKey, hash, 201, charge,
                    Charge.class);
            assertThat(count("http_idempotency")).isEqualTo(1);
            status.setRollbackOnly();
            return null;
        });

        assertThat(count("charge")).isZero();
        assertThat(count("charge_item")).isZero();
        assertThat(count("financial_movement")).isZero();
        assertThat(count("http_idempotency")).isZero();
    }

    @Test
    void unaTransaccionExteriorPuedePersistirCargoYPagoEnElMismoCommit() {
        transaction().execute(status -> {
            Charge charge = participant.createConfirmed(draft("100"), owner, NOW);
            participant.registerPayment(charge, PaymentMethod.CASH, new BigDecimal("40"), null,
                    owner.userId(), NOW);
            return null;
        });

        assertThat(count("charge")).isEqualTo(1);
        assertThat(count("charge_item")).isEqualTo(1);
        assertThat(count("financial_movement")).isEqualTo(1);
        assertThat(count("http_idempotency")).isZero();
    }

    @Test
    void elMetodoPublicoConservaCommitIndependienteAunqueElLlamadorSeRevierta() {
        String idempotencyKey = key();
        ConfirmChargeCommand command = command(ownAppointmentId, dentistId, "100", idempotencyKey);

        transaction().execute(status -> {
            operations.confirmCharge(command, owner);
            status.setRollbackOnly();
            return null;
        });

        assertThat(count("charge")).isEqualTo(1);
        assertThat(count("charge_item")).isEqualTo(1);
        assertThat(count("http_idempotency")).isEqualTo(1);
    }

    @Test
    void unParticipanteRechazaUnaTransaccionExteriorDeSoloLectura() {
        TransactionTemplate readOnly = transaction();
        readOnly.setReadOnly(true);

        assertThatThrownBy(() -> readOnly.execute(status -> {
            participant.createConfirmed(draft("100"), owner, NOW);
            return null;
        })).isInstanceOf(IllegalTransactionStateException.class);

        assertThat(count("charge")).isZero();
        assertThat(count("charge_item")).isZero();
    }

    @Test
    void unParticipanteRechazaUnAislamientoDistintoDeReadCommitted() {
        TransactionTemplate repeatableRead = transaction();
        repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        assertThatThrownBy(() -> repeatableRead.execute(status -> {
            participant.createConfirmed(draft("100"), owner, NOW);
            return null;
        })).isInstanceOf(IllegalTransactionStateException.class);

        assertThat(count("charge")).isZero();
        assertThat(count("charge_item")).isZero();
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactions);
    }

    private ChargeDraft draft(String amount) {
        return new ChargeDraft(patientId, ownAppointmentId,
                List.of(item(dentistId, "Concepto acordado", 1, amount)), "Nota interna");
    }
}

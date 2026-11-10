package com.dentalclinic.financial;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import com.dentalclinic.financial.application.command.CancelChargeCommand;
import com.dentalclinic.financial.application.command.ConfirmChargeCommand;
import com.dentalclinic.financial.application.command.RegisterPaymentCommand;
import com.dentalclinic.financial.application.command.ReversePaymentCommand;
import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.ChargeStatus;
import com.dentalclinic.financial.domain.FinancialConflictException;
import com.dentalclinic.financial.domain.FinancialMovement;
import com.dentalclinic.financial.domain.PaymentMethod;

/** Dos transacciones reales: barrera de inicio y espera de locks observada en PostgreSQL. */
class FinancialConcurrencyTest extends FinancialOperationsIntegrationSupport {

    @Test
    void dosPagosSimultaneosNoSuperanSaldo() throws Exception {
        Charge charge = confirmed("1000");
        List<Outcome> results = race(() -> payment(charge, "600", "one"),
                () -> payment(charge, "600", "two"), charge.getId());

        assertOneConflict(results);
        assertThat(count("financial_movement")).isEqualTo(1);
        assertThat(states.stateOf(charge.getId(), owner).appliedAmount()).isEqualByComparingTo("600");
        assertThat(states.stateOf(charge.getId(), owner).pendingBalance()).isEqualByComparingTo("400");
        assertThat(count("http_idempotency")).isEqualTo(2); // Cargo + pago ganador.
    }

    @Test
    void dosAltasSimultaneasMismaClaveDevuelvenUnSoloCargo() throws Exception {
        ConfirmChargeCommand command = command(null, null, "100", "concurrent-confirm");
        List<Outcome> results = race(() -> operations.confirmCharge(command, owner),
                () -> operations.confirmCharge(command, owner), null);

        assertBothSucceeded(results);
        assertThat(((Charge) results.get(0).value()).getId()).isEqualTo(((Charge) results.get(1).value()).getId());
        assertThat(count("charge")).isEqualTo(1);
        assertThat(count("charge_item")).isEqualTo(1);
        assertThat(count("http_idempotency")).isEqualTo(1);
    }

    @Test
    void mismaClaveSimultaneaConOtroPayloadConflictaSinDuplicarCargo() throws Exception {
        List<Outcome> results = race(() -> operations.confirmCharge(command(null, null, "100", "same"), owner),
                () -> operations.confirmCharge(command(null, null, "200", "same"), owner), null);

        assertOneConflict(results);
        assertThat(count("charge")).isEqualTo(1);
        assertThat(count("charge_item")).isEqualTo(1);
        assertThat(count("http_idempotency")).isEqualTo(1);
    }

    @Test
    void dosPagosMismaClaveNoDuplicanMovimientoAunqueElSaldoQuedeEnCero() throws Exception {
        Charge charge = confirmed("100");
        RegisterPaymentCommand command = new RegisterPaymentCommand(charge.getId(), PaymentMethod.CASH,
                new BigDecimal("100"), null, "same-payment");
        List<Outcome> results = race(() -> operations.registerPayment(command, owner),
                () -> operations.registerPayment(command, owner), charge.getId());

        assertBothSucceeded(results);
        assertThat(((FinancialMovement) results.get(0).value()).getId())
                .isEqualTo(((FinancialMovement) results.get(1).value()).getId());
        assertThat(count("financial_movement")).isEqualTo(1);
        assertThat(count("http_idempotency")).isEqualTo(2);
        assertThat(states.stateOf(charge.getId(), owner).status()).isEqualTo(ChargeStatus.PAGADO);
    }

    @Test
    void dosReversasDeUnPagoConDistintaClaveSoloCreanUnaReversa() throws Exception {
        Charge charge = confirmed("100");
        FinancialMovement original = payment(charge, "100", key());
        List<Outcome> results = race(() -> operations.reversePayment(new ReversePaymentCommand(charge.getId(),
                original.getId(), "Error", "one"), owner),
                () -> operations.reversePayment(new ReversePaymentCommand(charge.getId(), original.getId(),
                        "Error", "two"), owner), charge.getId());

        assertOneConflict(results);
        assertThat(count("financial_movement")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM financial_movement WHERE reverses_movement_id = ?",
                Long.class, original.getId())).isEqualTo(1L);
        assertThat(states.stateOf(charge.getId(), owner).status()).isEqualTo(ChargeStatus.PENDIENTE);
    }

    @Test
    void dosReversasMismaClaveHacenReplay() throws Exception {
        Charge charge = confirmed("100");
        FinancialMovement original = payment(charge, "100", key());
        ReversePaymentCommand command = new ReversePaymentCommand(charge.getId(), original.getId(), "Error", "same");
        List<Outcome> results = race(() -> operations.reversePayment(command, owner),
                () -> operations.reversePayment(command, owner), charge.getId());

        assertBothSucceeded(results);
        assertThat(((FinancialMovement) results.get(0).value()).getId())
                .isEqualTo(((FinancialMovement) results.get(1).value()).getId());
        assertThat(count("financial_movement")).isEqualTo(2);
        assertThat(count("http_idempotency")).isEqualTo(3);
    }

    @Test
    void pagoConcurrenteConAnulacionConservaUnaSolaOperacionValida() throws Exception {
        Charge charge = confirmed("100");
        List<Outcome> results = race(() -> payment(charge, "50", "pay"),
                () -> operations.cancelCharge(new CancelChargeCommand(charge.getId(), "Error", "cancel"), owner),
                charge.getId());

        assertOneConflict(results);
        boolean cancelled = jdbc.queryForObject("SELECT cancelled_at IS NOT NULL FROM charge WHERE id = ?",
                Boolean.class, charge.getId());
        if (cancelled) {
            assertThat(count("financial_movement")).isZero();
            assertThat(states.stateOf(charge.getId(), owner).status()).isEqualTo(ChargeStatus.ANULADO);
        } else {
            assertThat(count("financial_movement")).isEqualTo(1);
            assertThat(states.stateOf(charge.getId(), owner).pendingBalance()).isEqualByComparingTo("50");
        }
        assertThat(count("http_idempotency")).isEqualTo(2);
    }

    @Test
    void dosAnulacionesMismaClaveHacenReplaySinCambiarFechaOVersion() throws Exception {
        Charge charge = confirmed("100");
        CancelChargeCommand command = new CancelChargeCommand(charge.getId(), "Error", "same");
        List<Outcome> results = race(() -> operations.cancelCharge(command, owner),
                () -> operations.cancelCharge(command, owner), charge.getId());

        assertBothSucceeded(results);
        assertThat(results.get(0).value()).usingRecursiveComparison().isEqualTo(results.get(1).value());
        assertThat(count("http_idempotency")).isEqualTo(2);
    }

    private List<Outcome> race(Callable<?> first, Callable<?> second, Long lockedCharge) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Outcome>> futures;
            if (lockedCharge == null) {
                futures = startPair(executor, first, second, ready, start);
            } else {
                futures = new TransactionTemplate(transactions).execute(status -> {
                    jdbc.queryForObject("SELECT id FROM charge WHERE id = ? FOR UPDATE", Long.class, lockedCharge);
                    List<Future<Outcome>> waiting = startPair(executor, first, second, ready, start);
                    awaitTwoDatabaseWaiters(); // Ambas llamadas estan realmente bloqueadas antes de liberar la fila.
                    return waiting;
                });
            }
            return List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS));
        }
    }

    private List<Future<Outcome>> startPair(ExecutorService executor, Callable<?> first, Callable<?> second,
                                           CountDownLatch ready, CountDownLatch start) {
        List<Future<Outcome>> futures = List.of(executor.submit(() -> run(first, ready, start)),
                executor.submit(() -> run(second, ready, start)));
        try {
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        } finally {
            start.countDown();
        }
        return futures;
    }

    private static Outcome run(Callable<?> action, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("La barrera de concurrencia no se libero.");
        }
        try {
            return new Outcome(action.call(), null);
        } catch (RuntimeException failure) {
            return new Outcome(null, failure);
        }
    }

    private void awaitTwoDatabaseWaiters() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            long waiting = jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity "
                    + "WHERE datname = current_database() AND pid <> pg_backend_pid() "
                    + "AND wait_event_type = 'Lock' "
                    + "AND (query LIKE '%charge%' OR query LIKE '%pg_advisory_xact_lock%')", Long.class);
            if (waiting >= 2) {
                return;
            }
            try {
                Thread.sleep(20); // Poll acotado de una condicion observable, no demora arbitraria.
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        }
        throw new AssertionError("No se observaron las dos transacciones esperando locks de PostgreSQL.");
    }

    private static void assertOneConflict(List<Outcome> results) {
        assertThat(results.stream().filter(result -> result.failure() == null)).hasSize(1);
        assertThat(results.stream().filter(result -> result.failure() != null))
                .singleElement().satisfies(result -> assertThat(result.failure()).isInstanceOf(FinancialConflictException.class));
    }

    private static void assertBothSucceeded(List<Outcome> results) {
        assertThat(results).allSatisfy(result -> {
            assertThat(result.failure()).isNull();
            assertThat(result.value()).isNotNull();
        });
    }

    private record Outcome(Object value, RuntimeException failure) { }
}

package com.dentalclinic.financial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import com.dentalclinic.financial.FinancialOperationsIntegrationSupport;
import com.dentalclinic.financial.application.command.CancelChargeCommand;
import com.dentalclinic.financial.application.command.ConfirmChargeCommand;
import com.dentalclinic.financial.application.command.RegisterPaymentCommand;
import com.dentalclinic.financial.application.command.ReversePaymentCommand;
import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.DefinitiveFinancialConflictException;
import com.dentalclinic.financial.domain.DefinitiveInvalidChargeException;
import com.dentalclinic.financial.domain.FinancialConflictException;
import com.dentalclinic.financial.domain.FinancialIntent;
import com.dentalclinic.financial.domain.FinancialIntentClosedException;
import com.dentalclinic.financial.domain.FinancialIntentClosureBlockedException;
import com.dentalclinic.financial.domain.FinancialIntentClosureUncertainException;
import com.dentalclinic.financial.domain.FinancialIntentDisposition;
import com.dentalclinic.financial.domain.FinancialIntentOperation;
import com.dentalclinic.financial.domain.FinancialMovement;
import com.dentalclinic.financial.domain.FinancialRejectionReason;
import com.dentalclinic.financial.domain.InvalidChargeException;
import com.dentalclinic.financial.domain.PaymentMethod;
import com.dentalclinic.financial.infrastructure.repository.FinancialIntentRepository;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/** B3: locks, commit/rollback y cierres reales, solo PostgreSQL 16 efimero V001-V021. */
class FinancialIntentClosureIntegrationTest extends FinancialOperationsIntegrationSupport {
    @Autowired private FinancialIntentRegistrationService registration;
    @Autowired private FinancialIntentExecutionService execution;
    @MockitoSpyBean private FinancialIntentClosureService closures;
    @MockitoSpyBean private FinancialIntentRepository intents;
    @MockitoSpyBean private FinancialIdempotencyStore idempotency;
    @MockitoSpyBean(name = "transactionManager") private PlatformTransactionManager transactionManager;

    @ParameterizedTest
    @EnumSource(FinancialIntentOperation.class)
    void abandonoEsDurableIdempotenteYBloqueaAmbosEscritores(FinancialIntentOperation operation) {
        var c = valid(operation);
        var original = register(c);
        var ledger = ledger();
        var closed = closures.abandon(operation, c.key(), owner);
        assertThat(closed.disposition()).isEqualTo(FinancialIntentDisposition.ABANDONED);
        assertThat(closed.closedAt()).isNotNull();
        assertThat(closed.commandBody()).isEqualTo(original.commandBody());
        assertThat(closed.requestHash()).isEqualTo(original.requestHash());
        assertThat(closed.rejectionCode()).isNull();
        assertThat(closed.rejectedAttemptAt()).isNull();
        assertThat(closures.abandon(operation, c.key(), owner)).isEqualTo(closed);
        assertThat(register(c)).isEqualTo(closed);
        assertThatThrownBy(() -> execute(c)).isInstanceOf(FinancialIntentClosedException.class);
        assertThatThrownBy(() -> legacy(c)).isInstanceOf(FinancialIntentClosedException.class);
        assertThat(ledger()).isEqualTo(ledger);
        assertThat(receipts(c)).isZero();
    }

    @ParameterizedTest
    @MethodSource("executionRaces")
    void abandonoConcurrenteConNuevoOLegacySoloPermiteUnResultado(FinancialIntentOperation operation, boolean legacy) throws Exception {
        var c = valid(operation);
        register(c);
        var ledger = ledger();
        var outcomes = race(() -> closures.abandon(operation, c.key(), owner), () -> legacy ? legacy(c) : execute(c));
        if (receipts(c) == 1) {
            assertThat(disposition(c)).isEqualTo("OPEN");
            assertThat(outcomes.get(0)).isInstanceOf(FinancialIntentClosureBlockedException.class);
            assertThat(outcomes.get(1)).isNotInstanceOf(RuntimeException.class);
            var after = ledger();
            assertThat(execute(c)).usingRecursiveComparison().isEqualTo(outcomes.get(1));
            assertThat(ledger()).isEqualTo(after);
        } else {
            assertThat(receipts(c)).isZero();
            assertThat(disposition(c)).isEqualTo("ABANDONED");
            assertThat(outcomes.get(0)).isInstanceOf(FinancialIntent.class);
            assertThat(outcomes.get(1)).isInstanceOf(FinancialIntentClosedException.class);
            assertThat(ledger()).isEqualTo(ledger);
        }
    }

    @ParameterizedTest
    @EnumSource(FinancialIntentOperation.class)
    void reciboConfirmadoImpideAbandonarYSeConservaElSnapshot(FinancialIntentOperation operation) {
        var c = valid(operation);
        register(c);
        Object original = execute(c);
        var ledger = ledger();
        assertThatThrownBy(() -> closures.abandon(operation, c.key(), owner)).isInstanceOf(FinancialIntentClosureBlockedException.class);
        assertThat(disposition(c)).isEqualTo("OPEN");
        assertThat(execute(c)).usingRecursiveComparison().isEqualTo(original);
        assertThat(ledger()).isEqualTo(ledger);
        assertThat(receipts(c)).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(FinancialIntentOperation.class)
    void rechazoDeNegocioRealSoloSeCierraTrasRollbackYNoSePuedeReabrir(FinancialIntentOperation operation) {
        var c = rejected(operation);
        var original = register(c);
        var ledger = ledger();
        assertThatThrownBy(() -> execute(c)).isInstanceOfAny(FinancialConflictException.class, InvalidChargeException.class);
        assertRejected(c);
        var closed = register(c);
        assertThat(closed.commandBody()).isEqualTo(original.commandBody());
        assertThat(closed.requestHash()).isEqualTo(original.requestHash());
        assertThat(closed.commandVersion()).isEqualTo(original.commandVersion());
        assertThat(closed.rejectedAttemptAt()).isBetween(closed.registeredAt(), closed.closedAt());
        assertThatThrownBy(() -> execute(c)).isInstanceOf(FinancialIntentClosedException.class);
        assertThatThrownBy(() -> legacy(c)).isInstanceOf(FinancialIntentClosedException.class);
        assertThatThrownBy(() -> closures.abandon(operation, c.key(), owner)).isInstanceOf(FinancialIntentClosedException.class);
        assertThat(register(c)).isEqualTo(closed);
        assertThat(ledger()).isEqualTo(ledger);
        assertThat(receipts(c)).isZero();
    }

    @ParameterizedTest
    @EnumSource(FinancialIntentOperation.class)
    void rechazosConcurrentesNoDuplicanEfectosNiReescribenElPrimerCierre(FinancialIntentOperation operation) throws Exception {
        var c = rejected(operation);
        register(c);
        var before = ledger();
        var results = race(() -> execute(c), () -> execute(c));
        assertThat(results).allMatch(RuntimeException.class::isInstance);
        assertRejected(c);
        var closed = register(c);
        assertThatThrownBy(() -> execute(c)).isInstanceOf(FinancialIntentClosedException.class);
        assertThat(register(c)).isEqualTo(closed);
        assertThat(ledger()).isEqualTo(before);
    }

    @Test
    void dosAbandonosConcurrentesDevuelvenElMismoCierre() throws Exception {
        var c = valid(FinancialIntentOperation.FIN_REGISTER_PAYMENT);
        register(c);
        var results = race(() -> closures.abandon(c.operation(), c.key(), owner),
                () -> closures.abandon(c.operation(), c.key(), owner));
        assertThat(results.get(0)).isInstanceOf(FinancialIntent.class).isEqualTo(results.get(1));
        assertThat(disposition(c)).isEqualTo("ABANDONED");
        assertThat(receipts(c)).isZero();
    }

    @Test
    void b1SinRepresentacionRecuperablePermaneceOpenYAbandonoExplicitoEsSeguro() {
        Charge charge = confirmed("100");
        var command = new RegisterPaymentCommand(charge.getId(), PaymentMethod.CASH, new BigDecimal("1E+2"), null, key());
        var c = new Case(FinancialIntentOperation.FIN_REGISTER_PAYMENT, command.idempotencyKey(), command, charge.getId());
        register(c);
        new TransactionTemplate(transactions).execute(status -> {
            jdbc.update("UPDATE financial_intent SET command_version = 1, command_body = CAST(? AS jsonb) WHERE idempotency_key = ?",
                    idempotency.commandJson(command), c.key()); // Fixture B1 original, no migracion productiva.
            return null;
        });
        var before = ledger();
        assertThatThrownBy(() -> execute(c)).isInstanceOf(com.dentalclinic.financial.domain.FinancialIntentCommandUnavailableException.class);
        assertThat(disposition(c)).isEqualTo("OPEN");
        closures.abandon(c.operation(), c.key(), owner);
        assertThat(disposition(c)).isEqualTo("ABANDONED");
        assertThatThrownBy(() -> legacy(c)).isInstanceOf(FinancialIntentClosedException.class);
        assertThat(ledger()).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(FinancialIntentOperation.class)
    void rollbackRealDeEfectoYReciboFlusheadosSeAcreditaAntesDeCerrar(FinancialIntentOperation operation) {
        var c = valid(operation);
        register(c);
        var ledger = ledger();
        FinancialIdempotencyStore target = AopTestUtils.getUltimateTargetObject(idempotency);
        doAnswer(invocation -> {
            invocation.callRealMethod(); // Efecto y recibo realmente flusheados en PostgreSQL.
            assertThat(receipts(c)).isEqualTo(1); // Misma conexion transaccional, aun sin commit.
            throw businessFailure(operation);
        }).when(target).remember(eq(owner), eq(operation.name()), eq(c.key()), any(), anyInt(), any(), any());
        assertThatThrownBy(() -> execute(c)).isInstanceOfAny(FinancialConflictException.class, InvalidChargeException.class);
        assertRejected(c);
        assertThat(receipts(c)).isZero();
        assertThat(ledger()).isEqualTo(ledger);
    }

    @ParameterizedTest
    @MethodSource("technicalFailures")
    void timeoutConexionYDeadlockConservanOpenInclusoDespuesDelFlush(FinancialIntentOperation operation, String kind) {
        var c = valid(operation);
        register(c);
        var ledger = ledger();
        RuntimeException error = switch (kind) {
            case "timeout" -> new QueryTimeoutException("timeout sintetico");
            case "connection" -> new DataAccessResourceFailureException("conexion sintetica perdida");
            default -> new CannotAcquireLockException("deadlock sintetico");
        };
        FinancialIdempotencyStore target = AopTestUtils.getUltimateTargetObject(idempotency);
        doAnswer(invocation -> { invocation.callRealMethod(); throw error; })
                .when(target).remember(eq(owner), eq(operation.name()), eq(c.key()), any(), anyInt(), any(), any());
        assertThatThrownBy(() -> execute(c)).isSameAs(error);
        assertThat(disposition(c)).isEqualTo("OPEN");
        assertThat(receipts(c)).isZero();
        assertThat(ledger()).isEqualTo(ledger);
        reset(target);
        var original = execute(c);
        assertThat(execute(c)).usingRecursiveComparison().isEqualTo(original);
        assertThat(receipts(c)).isEqualTo(1);
    }

    @Test
    void falloAlConfirmarRollbackNoAutorizaCierreAunqueElErrorOriginalEraDeNegocio() {
        var c = rejected(FinancialIntentOperation.FIN_REGISTER_PAYMENT);
        register(c);
        var ledger = ledger();
        PlatformTransactionManager target = AopTestUtils.getUltimateTargetObject(transactionManager);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new TransactionSystemException("confirmacion de rollback perdida");
        }).when(target).rollback(any());
        assertThatThrownBy(() -> execute(c)).isInstanceOf(TransactionSystemException.class);
        assertThat(disposition(c)).isEqualTo("OPEN");
        assertThat(receipts(c)).isZero();
        assertThat(ledger()).isEqualTo(ledger);
    }

    @Test
    void falloTrasUpdateDeRechazoRevierteSoloElCierreYNoSeDeclaraRejected() {
        var c = rejected(FinancialIntentOperation.FIN_REGISTER_PAYMENT);
        register(c);
        var ledger = ledger();
        FinancialIntentRepository target = AopTestUtils.getUltimateTargetObject(intents);
        doAnswer(invocation -> { invocation.callRealMethod(); throw new QueryTimeoutException("fallo de finalizacion"); })
                .when(target).closeOpen(any(), any(), any(), any(), any());
        assertThatThrownBy(() -> execute(c)).isInstanceOf(FinancialIntentClosureUncertainException.class);
        assertThat(disposition(c)).isEqualTo("OPEN");
        assertThat(ledger()).isEqualTo(ledger);
        assertThat(receipts(c)).isZero();
        reset(target);
        assertThatThrownBy(() -> execute(c)).isInstanceOf(FinancialConflictException.class);
        assertRejected(c);
    }

    @Test
    void falloTrasUpdateDeAbandonoNoDejaCierreParcial() {
        var c = valid(FinancialIntentOperation.FIN_CONFIRM_CHARGE);
        register(c);
        FinancialIntentRepository target = AopTestUtils.getUltimateTargetObject(intents);
        doAnswer(invocation -> { invocation.callRealMethod(); throw new QueryTimeoutException("fallo de cierre"); })
                .when(target).closeOpen(any(), any(), any(), any(), any());
        assertThatThrownBy(() -> closures.abandon(c.operation(), c.key(), owner)).isInstanceOf(QueryTimeoutException.class);
        assertThat(disposition(c)).isEqualTo("OPEN");
        assertThat(receipts(c)).isZero();
        reset(target);
        assertThat(closures.abandon(c.operation(), c.key(), owner).disposition()).isEqualTo(FinancialIntentDisposition.ABANDONED);
    }

    @Test
    void reciboIlegibleTambienImpideAbandono() {
        var c = valid(FinancialIntentOperation.FIN_REGISTER_PAYMENT);
        register(c);
        execute(c);
        jdbc.update("UPDATE http_idempotency SET response_body = '{}'::jsonb WHERE idempotency_key = ?", c.key());
        var ledger = ledger();
        assertThatThrownBy(() -> closures.abandon(c.operation(), c.key(), owner)).isInstanceOf(FinancialIntentClosureBlockedException.class);
        assertThat(disposition(c)).isEqualTo("OPEN");
        assertThat(ledger()).isEqualTo(ledger);
    }

    @Test
    void uuidAjenoPermisosYAusenciaNoPermitenAbandonar() {
        var c = valid(FinancialIntentOperation.FIN_REGISTER_PAYMENT);
        register(c);
        assertThatThrownBy(() -> closures.abandon(c.operation(), c.key(), reception)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> closures.abandon(c.operation(), c.key(), new AuthenticatedUser(owner.userId(), Role.DENTIST)))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> closures.abandon(c.operation(), key(), owner)).isInstanceOf(NotFoundException.class);
        assertThat(disposition(c)).isEqualTo("OPEN");
    }

    @Test
    void rechazoSinEvidenciaDeRollbackNoEsUnaOperacionDisponible() {
        var c = valid(FinancialIntentOperation.FIN_REGISTER_PAYMENT);
        register(c);
        assertThatThrownBy(() -> closures.rejectAfterRollback(new FinancialIntentExecutionAttempt(owner, c.operation(), c.key()),
                businessFailure(c.operation()))).isInstanceOf(IllegalStateException.class);
        assertThat(disposition(c)).isEqualTo("OPEN");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void ganadorEntreRollbackYCierreNoEsSobrescrito(boolean successWins) throws Exception {
        var c = rejected(FinancialIntentOperation.FIN_REGISTER_PAYMENT);
        register(c);
        var paid = jdbc.queryForObject("SELECT id FROM financial_movement WHERE charge_id = ?", Long.class, c.charge());
        CountDownLatch rolledBack = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        FinancialIntentClosureService target = AopTestUtils.getUltimateTargetObject(closures);
        doAnswer(invocation -> {
            FinancialIntentExecutionAttempt evidence = invocation.getArgument(0);
            assertThat(evidence.provesRollbackFor(invocation.getArgument(1))).isTrue();
            assertThat(receipts(c)).isZero();
            rolledBack.countDown();
            await(proceed);
            return invocation.callRealMethod();
        }).when(target).rejectAfterRollback(any(), any());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var pending = pool.submit(() -> outcome(() -> execute(c)));
            try {
                assertThat(rolledBack.await(10, TimeUnit.SECONDS)).isTrue();
                if (successWins) {
                    operations.reversePayment(new ReversePaymentCommand(c.charge(), paid, "revertir abono", key()), owner);
                    legacy(c); // Ahora cabe el pago: otro escritor gana con la MISMA identidad.
                } else {
                    closures.abandon(c.operation(), c.key(), owner);
                }
            } finally {
                proceed.countDown();
            }
            var result = pending.get(15, TimeUnit.SECONDS);
            if (successWins) {
                assertThat(result).isInstanceOf(FinancialIntentClosureBlockedException.class);
                assertThat(disposition(c)).isEqualTo("OPEN");
                assertThat(receipts(c)).isEqualTo(1);
            } else {
                assertThat(result).isInstanceOf(FinancialIntentClosedException.class);
                assertThat(disposition(c)).isEqualTo("ABANDONED");
                assertThat(receipts(c)).isZero();
            }
        }
    }

    @Test
    void lockTimeoutRealDePostgresConservaOpen() throws Exception {
        var c = valid(FinancialIntentOperation.FIN_REGISTER_PAYMENT);
        register(c);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FinancialIdempotencyStore target = AopTestUtils.getUltimateTargetObject(idempotency);
        // Configuracion LOCAL de esa transaccion efimera, nunca parametro de instalacion.
        doAnswer(invocation -> {
            jdbc.execute("SET LOCAL lock_timeout = '100ms'");
            return invocation.callRealMethod();
        }).when(target).readCommand(any(), any());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var holder = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                jdbc.queryForObject("SELECT id FROM charge WHERE id = ? FOR UPDATE", Long.class, c.charge());
                locked.countDown();
                await(release);
                return null;
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                Throwable failure = catchThrowable(() -> execute(c));
                assertThat(failure).isInstanceOf(org.springframework.dao.PessimisticLockingFailureException.class);
                assertThat(sqlState(failure)).isEqualTo("55P03");
                assertThat(disposition(c)).isEqualTo("OPEN");
                assertThat(receipts(c)).isZero();
            } finally {
                release.countDown();
                holder.get(15, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void deadlockRealDePostgresNuncaSeConvierteEnRechazoDurable() throws Exception {
        var c = valid(FinancialIntentOperation.FIN_REGISTER_PAYMENT);
        register(c);
        var before = ledger();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch takeIdentity = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            // Transaccion adversaria SOLO de prueba que invierte intencionalmente el orden.
            var adversary = pool.submit(() -> outcome(() -> new TransactionTemplate(transactions).execute(status -> {
                jdbc.queryForObject("SELECT id FROM charge WHERE id = ? FOR UPDATE", Long.class, c.charge());
                locked.countDown();
                await(takeIdentity);
                idempotency.lockIdentity(owner, FinancialIdempotencyStore.FINANCIAL_NAMESPACE, c.operation().name(), c.key());
                return null;
            })));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var executing = pool.submit(() -> outcome(() -> execute(c)));
            try {
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    waiting = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database() "
                            + "AND wait_event_type = 'Lock' AND query LIKE '%from charge%' AND pid <> pg_backend_pid())", Boolean.class);
                    if (waiting) break;
                    Thread.onSpinWait();
                }
                assertThat(waiting).isTrue();
            } finally {
                takeIdentity.countDown();
            }
            Object other = adversary.get(15, TimeUnit.SECONDS);
            Object result = executing.get(15, TimeUnit.SECONDS);
            assertThat(Stream.of(other, result).anyMatch(value -> value instanceof Throwable error && "40P01".equals(sqlState(error)))).isTrue();
            assertThat(disposition(c)).isEqualTo("OPEN");
            if (result instanceof RuntimeException) {
                assertThat(receipts(c)).isZero();
                assertThat(ledger()).isEqualTo(before);
            } else {
                assertThat(receipts(c)).isEqualTo(1);
                var committed = ledger();
                assertThat(execute(c)).usingRecursiveComparison().isEqualTo(result);
                assertThat(ledger()).isEqualTo(committed);
            }
        }
    }

    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql) return sql.getSQLState();
        }
        return null;
    }

    private Case valid(FinancialIntentOperation operation) {
        String key = key();
        if (operation == FinancialIntentOperation.FIN_CONFIRM_CHARGE) {
            return new Case(operation, key, command(ownAppointmentId, dentistId, "100", key), null);
        }
        Charge charge = confirmed("100");
        Object command = switch (operation) {
            case FIN_REGISTER_PAYMENT -> new RegisterPaymentCommand(charge.getId(), PaymentMethod.CARD, new BigDecimal("100"), null, key);
            case FIN_REVERSE_PAYMENT -> new ReversePaymentCommand(charge.getId(), payment(charge, "100", key()).getId(), "motivo", key);
            case FIN_CANCEL_CHARGE -> new CancelChargeCommand(charge.getId(), "motivo", key);
            default -> throw new IllegalArgumentException();
        };
        return new Case(operation, key, command, charge.getId());
    }

    private Case rejected(FinancialIntentOperation operation) {
        if (operation == FinancialIntentOperation.FIN_CONFIRM_CHARGE) {
            String key = key();
            return new Case(operation, key, new ConfirmChargeCommand(patientId, null, List.of(), null, key), null);
        }
        var c = valid(operation);
        if (operation == FinancialIntentOperation.FIN_REVERSE_PAYMENT) {
            var original = (ReversePaymentCommand) c.command();
            operations.reversePayment(new ReversePaymentCommand(c.charge(), original.movementId(), "correccion", key()), owner);
        } else {
            operations.registerPayment(new RegisterPaymentCommand(c.charge(), PaymentMethod.CASH, new BigDecimal("40"), null, key()), owner);
        }
        return c;
    }

    private FinancialIntent register(Case c) {
        return switch (c.operation()) {
            case FIN_CONFIRM_CHARGE -> registration.register((ConfirmChargeCommand) c.command(), owner);
            case FIN_REGISTER_PAYMENT -> registration.register((RegisterPaymentCommand) c.command(), owner);
            case FIN_REVERSE_PAYMENT -> registration.register((ReversePaymentCommand) c.command(), owner);
            case FIN_CANCEL_CHARGE -> registration.register((CancelChargeCommand) c.command(), owner);
        };
    }

    private Object execute(Case c) {
        return switch (c.operation()) {
            case FIN_CONFIRM_CHARGE -> execution.confirmCharge(c.key(), owner);
            case FIN_REGISTER_PAYMENT -> execution.registerPayment(c.key(), owner);
            case FIN_REVERSE_PAYMENT -> execution.reversePayment(c.key(), owner);
            case FIN_CANCEL_CHARGE -> execution.cancelCharge(c.key(), owner);
        };
    }

    private Object legacy(Case c) {
        return switch (c.operation()) {
            case FIN_CONFIRM_CHARGE -> operations.confirmCharge((ConfirmChargeCommand) c.command(), owner);
            case FIN_REGISTER_PAYMENT -> operations.registerPayment((RegisterPaymentCommand) c.command(), owner);
            case FIN_REVERSE_PAYMENT -> operations.reversePayment((ReversePaymentCommand) c.command(), owner);
            case FIN_CANCEL_CHARGE -> operations.cancelCharge((CancelChargeCommand) c.command(), owner);
        };
    }

    private long receipts(Case c) {
        return jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE actor_user_id = ? AND operation = ? AND idempotency_key = ?",
                Long.class, owner.userId(), c.operation().name(), c.key());
    }

    private String disposition(Case c) {
        return jdbc.queryForObject("SELECT disposition FROM financial_intent WHERE idempotency_key = ?", String.class, c.key());
    }

    private void assertRejected(Case c) {
        var stored = register(c);
        var reason = reason(c.operation());
        assertThat(stored.disposition()).isEqualTo(FinancialIntentDisposition.REJECTED);
        assertThat(stored.rejectionCode()).isEqualTo(reason.name());
        assertThat(stored.rejectionHttpStatus()).isEqualTo(reason.httpStatus());
        assertThat(stored.rejectedAttemptAt()).isNotNull();
    }

    private List<List<String>> ledger() {
        return Stream.of("charge", "charge_item", "financial_movement", "http_idempotency")
                .map(t -> jdbc.queryForList("SELECT to_jsonb(t)::text FROM " + t + " t ORDER BY to_jsonb(t)::text", String.class)).toList();
    }

    private static FinancialRejectionReason reason(FinancialIntentOperation operation) {
        return switch (operation) {
            case FIN_CONFIRM_CHARGE -> FinancialRejectionReason.CHARGE_ITEMS_REQUIRED;
            case FIN_REGISTER_PAYMENT -> FinancialRejectionReason.PAYMENT_EXCEEDS_BALANCE;
            case FIN_REVERSE_PAYMENT -> FinancialRejectionReason.PAYMENT_ALREADY_REVERSED;
            case FIN_CANCEL_CHARGE -> FinancialRejectionReason.CHARGE_HAS_PAYMENTS;
        };
    }

    private static RuntimeException businessFailure(FinancialIntentOperation operation) {
        var reason = reason(operation);
        return reason.httpStatus() == 400 ? new DefinitiveInvalidChargeException(reason, "rechazo sintetico tras escritura")
                : new DefinitiveFinancialConflictException(reason, "rechazo sintetico tras escritura");
    }

    private static List<Object> race(Callable<Object> first, Callable<Object> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream().map(call -> pool.submit(() -> {
                ready.countDown();
                await(start);
                return outcome(call);
            })).toList();
            try { assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { start.countDown(); }
            var result = new ArrayList<Object>();
            for (var future : futures) result.add(future.get(20, TimeUnit.SECONDS));
            return result;
        }
    }

    private static Object outcome(Callable<Object> call) throws Exception {
        try { return call.call(); }
        catch (RuntimeException ex) { return ex; }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("timeout de prueba");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    static Stream<Arguments> executionRaces() {
        return Stream.of(FinancialIntentOperation.values()).flatMap(op -> Stream.of(false, true).map(legacy -> Arguments.of(op, legacy)));
    }

    static Stream<Arguments> technicalFailures() {
        return Stream.of(FinancialIntentOperation.values()).flatMap(op -> Stream.of("timeout", "connection", "deadlock").map(kind -> Arguments.of(op, kind)));
    }

    private record Case(FinancialIntentOperation operation, String key, Object command, Long charge) { }
}

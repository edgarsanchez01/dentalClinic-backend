package com.dentalclinic.financial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.dentalclinic.financial.application.command.CancelChargeCommand;
import com.dentalclinic.financial.application.command.ChargeItemData;
import com.dentalclinic.financial.application.command.ConfirmChargeCommand;
import com.dentalclinic.financial.application.command.RegisterPaymentCommand;
import com.dentalclinic.financial.application.command.ReversePaymentCommand;
import com.dentalclinic.financial.domain.FinancialIntent;
import com.dentalclinic.financial.domain.FinancialIntentCommandConflictException;
import com.dentalclinic.financial.domain.FinancialIntentDisposition;
import com.dentalclinic.financial.domain.FinancialIntentOperation;
import com.dentalclinic.financial.domain.PaymentMethod;
import com.dentalclinic.financial.infrastructure.repository.FinancialIntentRepository;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.ObjectMapper;

/** DDL V001-V021 y transacciones reales, URL forzada exclusivamente a PostgreSQL 16 efimero. */
@SpringBootTest
@ActiveProfiles("test")
class FinancialIntentRegistrationIntegrationTest {
    private static final String URL = "jdbc:tc:postgresql:16:///dentalclinic_financial_intent_b1";

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.driver-class-name", () -> "org.testcontainers.jdbc.ContainerDatabaseDriver");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "2");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.sql.init.mode", () -> "always");
        // Solo los scripts versionados aprobados, ordenados; nunca una URL/DDL de la instalacion local.
        try (var paths = Files.list(Path.of("../db/schema"))) {
            String scripts = paths.filter(p -> p.getFileName().toString().matches("V0(0[1-9]|1[0-9]|2[01])__.*\\.sql"))
                    .sorted().map(p -> "file:" + p).collect(Collectors.joining(","));
            assertThat(scripts.split(",")).hasSize(21);
            registry.add("spring.sql.init.schema-locations", () -> scripts);
        }
        for (String flag : List.of("communication.email.enabled", "communication.dispatch.enabled",
                "appointment.reminder.enabled", "appointment.reminder.email-delivery-enabled",
                "identity.email.dispatch-enabled", "identity.links-enabled", "fixtures.enabled")) {
            registry.add("dentalclinic." + flag, () -> "false");
        }
    }

    @Autowired private FinancialIntentRegistrationService service;
    @Autowired private FinancialIdempotencyStore idempotency;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Environment environment;
    @Autowired private ObjectMapper mapper;
    @MockitoSpyBean private FinancialIntentRepository repository;
    private AuthenticatedUser owner;
    private AuthenticatedUser reception;

    @BeforeEach
    void seedOnlyEphemeralDatabase() throws Exception {
        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo(URL);
        assertThat(environment.getProperty("spring.datasource.driver-class-name"))
                .isEqualTo("org.testcontainers.jdbc.ContainerDatabaseDriver");
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).doesNotContain(":5434/");
            assertThat(connection.getMetaData().getDatabaseProductVersion()).startsWith("16.");
        }
        jdbc.update("DELETE FROM financial_intent");
        jdbc.update("DELETE FROM http_idempotency");
        jdbc.update("DELETE FROM users");
        owner = user(Role.OWNER);
        reception = user(Role.RECEPTIONIST);
    }

    @Test
    void registraCuatroComandosSinEfectosNiRecibos() throws Exception {
        String key = key();
        var charge = new ConfirmChargeCommand(123L, 456L, List.of(new ChargeItemData(null, null,
                "Servicio sintético", 1, new BigDecimal("1234567890123456.7890123400"))), "nota", key);
        var payment = payment(key, "0.1000000000000000000100");
        var reversal = new ReversePaymentCommand(789L, 123L, "motivo", key);
        var cancellation = new CancelChargeCommand(789L, "motivo", key);
        List<FinancialIntent> intents = List.of(service.register(charge, owner), service.register(payment, owner),
                service.register(reversal, owner), service.register(cancellation, owner));
        assertThat(intents).extracting(FinancialIntent::operation).containsExactly(FinancialIntentOperation.values());
        for (FinancialIntent intent : intents) {
            assertThat(intent.disposition()).isEqualTo(FinancialIntentDisposition.OPEN);
            assertThat(intent.commandVersion()).isEqualTo(2);
            assertThat(intent.hashVersion()).isEqualTo(1);
            assertThat(intent.closedAt()).isNull();
            assertThat(intent.commandPurgedAt()).isNull();
            assertThat(intent.rejectionCode()).isNull();
        }
        assertThat(mapper.readValue(intents.get(0).commandBody(), ConfirmChargeCommand.class)).isEqualTo(charge);
        assertThat(mapper.readValue(intents.get(1).commandBody(), RegisterPaymentCommand.class)).isEqualTo(payment);
        assertThat(mapper.readValue(intents.get(2).commandBody(), ReversePaymentCommand.class)).isEqualTo(reversal);
        assertThat(mapper.readValue(intents.get(3).commandBody(), CancelChargeCommand.class)).isEqualTo(cancellation);
        var restored = mapper.readValue(intents.get(1).commandBody(), RegisterPaymentCommand.class);
        String restoredHash = transaction().execute(status -> idempotency.lockRequest(owner,
                FinancialIdempotencyStore.FINANCIAL_NAMESPACE, "FIN_REGISTER_PAYMENT", key, restored));
        assertThat(restoredHash).isEqualTo(intents.get(1).requestHash());
        assertNoFinancialEffects();
    }

    @Test
    void repetirRegistroDevuelveElOriginalSinReescribirComandoNiFecha() {
        var command = payment(key(), "12.3400");
        FinancialIntent first = service.register(command, owner);
        assertThat(service.register(command, owner)).isEqualTo(first);
        assertThat(count("financial_intent")).isEqualTo(1);
        assertNoFinancialEffects();
    }

    @Test
    void hashCoincideExactamenteConIdempotenciaExistenteInclusoEscalaDecimal() {
        var command = payment(key(), "12.3400");
        var intent = service.register(command, owner);
        String legacyHash = transaction().execute(status -> idempotency.lockRequest(owner,
                FinancialIdempotencyStore.FINANCIAL_NAMESPACE, intent.operation().name(), command.idempotencyKey(), command));
        assertThat(intent.requestHash()).isEqualTo(legacyHash);
        assertThatThrownBy(() -> service.register(payment(command.idempotencyKey(), "12.34"), owner))
                .isInstanceOf(FinancialIntentCommandConflictException.class);
        assertThat(service.register(command, owner)).isEqualTo(intent);
    }

    @Test
    void mismaClaveEnOtrosActoresYOperacionesNoSeDeduplica() {
        String key = key();
        var command = payment(key, "10");
        var first = service.register(command, owner);
        var second = service.register(command, reception);
        var third = service.register(new CancelChargeCommand(789L, "motivo", key), owner);
        assertThat(first.actorUserId()).isEqualTo(owner.userId());
        assertThat(second.actorUserId()).isEqualTo(reception.userId());
        assertThat(third.operation()).isEqualTo(FinancialIntentOperation.FIN_CANCEL_CHARGE);
        assertThat(count("financial_intent")).isEqualTo(3);
    }

    @Test
    void contenidoSimilarConClavesDistintasNoSeDeduplica() {
        service.register(payment(key(), "10"), owner);
        service.register(payment(key(), "10"), owner);
        assertThat(count("financial_intent")).isEqualTo(2);
    }

    @Test
    void conflictoConcurrenteConservaUnSoloComando() throws Exception {
        String key = key();
        List<Object> results = concurrent(List.of(
                () -> registerOrConflict(payment(key, "10")), () -> registerOrConflict(payment(key, "11"))));
        assertThat(results.stream().filter(FinancialIntent.class::isInstance).count()).isEqualTo(1);
        assertThat(results.stream().filter(FinancialIntentCommandConflictException.class::isInstance).count()).isEqualTo(1);
        assertThat(count("financial_intent")).isEqualTo(1);
        assertNoFinancialEffects();
    }

    @Test
    void ochoRegistrosConcurrentesIdenticosConvergenAlMismoRegistro() throws Exception {
        var command = payment(key(), "10.00");
        List<Callable<FinancialIntent>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> service.register(command, owner));
        }
        List<FinancialIntent> results = concurrent(calls);
        assertThat(results).hasSize(8).allMatch(results.getFirst()::equals);
        assertThat(count("financial_intent")).isEqualTo(1);
        assertNoFinancialEffects();
    }

    @Test
    void comparteLockConEscritorFinancieroAnterior() throws Exception {
        var command = payment(key(), "10");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entering = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var legacy = pool.submit(() -> transaction().execute(status -> {
                idempotency.lockRequest(owner, FinancialIdempotencyStore.FINANCIAL_NAMESPACE,
                        "FIN_REGISTER_PAYMENT", command.idempotencyKey(), command);
                locked.countDown();
                await(release);
                return null;
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var registering = pool.submit(() -> {
                    entering.countDown();
                    return service.register(command, owner);
                });
                assertThat(entering.await(10, TimeUnit.SECONDS)).isTrue();
                // Verifica espera REAL por lock en PostgreSQL, no un sleep ni solo Future.isDone().
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    waiting = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_stat_activity "
                            + "WHERE datname = current_database() AND wait_event = 'advisory')", Boolean.class);
                    if (waiting) break;
                    Thread.onSpinWait();
                }
                assertThat(waiting).isTrue();
                assertThat(registering.isDone()).isFalse();
                release.countDown();
                assertThat(registering.get(10, TimeUnit.SECONDS).disposition()).isEqualTo(FinancialIntentDisposition.OPEN);
            } finally {
                release.countDown();
                legacy.get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void falloDespuesDelInsertRevierteTodaLaIntencion() {
        FinancialIntentRepository target = AopTestUtils.getUltimateTargetObject(repository);
        doThrow(new IllegalStateException("fallo inyectado despues del insert"))
                .when(target).findByIdentityForUpdate(any(), any(), any());
        assertThatThrownBy(() -> service.register(payment(key(), "10"), owner))
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fallo inyectado despues del insert");
        assertThat(count("financial_intent")).isZero();
        assertNoFinancialEffects();
    }

    @Test
    void registroTieneCommitIndependienteDelRollbackDelLlamador() {
        var command = payment(key(), "10");
        TransactionTemplate outer = transaction();
        outer.setReadOnly(true);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.execute(status -> {
            service.register(command, owner);
            status.setRollbackOnly();
            return null;
        });
        assertThat(count("financial_intent")).isEqualTo(1);
    }

    @Test
    void registroNoReabreIntencionesCerradas() {
        for (String state : List.of("ABANDONED", "REJECTED")) {
            var command = payment(key(), "10");
            var first = service.register(command, owner);
            // Fixture SQL, NO API/servicio de cierre implementado por B1.
            String rejection = state.equals("REJECTED")
                    ? ", rejected_attempt_at = registered_at, rejection_code = 'FIXTURE', rejection_http_status = 409" : "";
            jdbc.update("UPDATE financial_intent SET disposition = ?, closed_at = registered_at " + rejection
                    + " WHERE actor_user_id = ? AND operation = ? AND idempotency_key = ?",
                    state, owner.userId(), first.operation().name(), first.idempotencyKey());
            var repeated = service.register(command, owner);
            assertThat(repeated.disposition().name()).isEqualTo(state);
            assertThat(repeated.commandBody()).isEqualTo(first.commandBody());
            assertThat(repeated.registeredAt()).isEqualTo(first.registeredAt());
        }
    }

    @Test
    void reciboPrevioConHashDistintoImpideRegistroSinModificarRecibo() {
        var command = payment(key(), "10");
        jdbc.update("INSERT INTO http_idempotency (actor_user_id, operation, idempotency_key, request_hash, response_status, response_body) "
                + "VALUES (?, 'FIN_REGISTER_PAYMENT', ?, ?, 201, '{\"snapshotVersion\":1}')",
                owner.userId(), command.idempotencyKey(), "f".repeat(64));
        assertThatThrownBy(() -> service.register(command, owner)).isInstanceOf(FinancialIntentCommandConflictException.class);
        assertThat(count("financial_intent")).isZero();
        assertThat(count("http_idempotency")).isEqualTo(1);
    }

    @Test
    void reciboPrevioCompatibleNoSeDuplicaNiSeGuardaEnLaIntencion() {
        var command = payment(key(), "10");
        String hash = transaction().execute(status -> idempotency.lockRequest(owner,
                FinancialIdempotencyStore.FINANCIAL_NAMESPACE, "FIN_REGISTER_PAYMENT", command.idempotencyKey(), command));
        jdbc.update("INSERT INTO http_idempotency (actor_user_id, operation, idempotency_key, request_hash, response_status, response_body) "
                + "VALUES (?, 'FIN_REGISTER_PAYMENT', ?, ?, 201, '{\"snapshotVersion\":1,\"result\":{\"id\":123}}')",
                owner.userId(), command.idempotencyKey(), hash);
        String receipt = jdbc.queryForObject("SELECT response_body::text FROM http_idempotency", String.class);
        var intent = service.register(command, owner);
        assertThat(intent.disposition()).isEqualTo(FinancialIntentDisposition.OPEN);
        assertThat(intent.commandBody()).doesNotContain("snapshotVersion", "result");
        assertThat(jdbc.queryForObject("SELECT response_body::text FROM http_idempotency", String.class)).isEqualTo(receipt);
        assertThat(count("http_idempotency")).isEqualTo(1);
    }

    @Test
    void actorInexistenteNoDejaIntencionYRecepcionRegistraSoloAltaOPago() {
        assertThatThrownBy(() -> service.register(payment(key(), "10"), new AuthenticatedUser(Long.MAX_VALUE, Role.OWNER)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("financial_intent")).isZero();
        service.register(new ConfirmChargeCommand(123L, null, List.of(), null, key()), reception);
        service.register(payment(key(), "10"), reception);
        assertThat(count("financial_intent")).isEqualTo(2);
        assertNoFinancialEffects();
    }

    @Test
    void ddlTienePKFKChecksIndicePendientesSinBackfillNiVersionAutomatica() {
        assertThat(count("financial_intent")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_schema_version WHERE script_name LIKE 'V021%'", Long.class)).isZero();
        var intent = service.register(payment(key(), "10"), owner);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO financial_intent SELECT * FROM financial_intent"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", owner.userId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        for (String assignment : List.of("operation = 'APPOINTMENT_COLLECTION'", "idempotency_key = 'not-a-uuid'",
                "command_version = 0", "hash_version = 0", "request_hash = 'invalid'", "command_body = '[]'::jsonb",
                "command_body = 'null'::jsonb", "command_body = NULL", "disposition = 'CONFIRMED'",
                "disposition = 'REJECTED'", "disposition = 'ABANDONED'", "closed_at = now()",
                "command_purged_at = now()", "rejection_code = 'UNEXPECTED'")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE financial_intent SET " + assignment))
                    .as(assignment).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'financial_intent_pending_idx'", String.class))
                .contains("actor_user_id", "WHERE", "OPEN");
        assertThat(service.register(payment(intent.idempotencyKey(), "10"), owner)).isEqualTo(intent);
    }

    @Test
    void ddlSeRevierteTransaccionalmenteSinAfectarDatosAnteriores() throws Exception {
        var command = payment(key(), "10");
        var intent = service.register(command, owner);
        String ddl = Files.readString(Path.of("../db/schema/V021__financial_intent.sql"));
        transaction().execute(status -> {
            jdbc.execute("DROP TABLE financial_intent"); // Solo esta base efimera; DDL transaccional.
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
                    org.springframework.jdbc.datasource.DataSourceUtils.getConnection(jdbc.getDataSource()),
                    new org.springframework.core.io.ByteArrayResource(ddl.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertThat(count("financial_intent")).isZero();
            status.setRollbackOnly();
            return null;
        });
        assertThat(service.register(command, owner)).isEqualTo(intent);
        assertThat(count("users")).isEqualTo(2);
    }

    private AuthenticatedUser user(Role role) {
        return new AuthenticatedUser(jdbc.queryForObject("INSERT INTO users (email, password_hash, role, active, created_at) "
                + "VALUES (?, 'synthetic-only', ?, TRUE, now()) RETURNING id", Long.class,
                key() + "@intent.test", role.name()), role);
    }

    private TransactionTemplate transaction() {
        var tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx;
    }

    private RegisterPaymentCommand payment(String key, String amount) {
        return new RegisterPaymentCommand(789L, PaymentMethod.CARD, new BigDecimal(amount), "nota", key);
    }

    private Object registerOrConflict(RegisterPaymentCommand command) {
        try { return service.register(command, owner); }
        catch (FinancialIntentCommandConflictException conflict) { return conflict; }
    }

    private static <T> List<T> concurrent(List<Callable<T>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(calls.size())) {
            var futures = calls.stream().map(call -> pool.submit(() -> {
                ready.countDown();
                await(start);
                return call.call();
            })).toList();
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            List<T> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("timeout de prueba");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private void assertNoFinancialEffects() {
        for (String table : List.of("charge", "charge_item", "financial_movement", "appointment", "appointment_checkout", "http_idempotency")) {
            assertThat(count(table)).as(table).isZero();
        }
    }

    private static String key() { return UUID.randomUUID().toString(); }
}

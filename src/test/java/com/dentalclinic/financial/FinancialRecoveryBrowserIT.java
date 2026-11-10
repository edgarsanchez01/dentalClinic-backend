package com.dentalclinic.financial;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import com.fasterxml.jackson.databind.ObjectMapper;

/** B6 opt-in only: mvn -Dtest=FinancialRecoveryBrowserIT test. No mocks or existing database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "dentalclinic.security.cors.allowed-origins=http://127.0.0.1:5199")
class FinancialRecoveryBrowserIT extends FinancialOperationsIntegrationSupport {
    @LocalServerPort int port;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper mapper;

    @Test void manualRecoveryThroughRealBrowserHttpAndIsolatedPostgres() throws Exception {
        String password = "B6-Synthetic-Password-2026!";
        String hash = passwords.encode(password);
        jdbc.update("UPDATE users SET password_hash=?", hash);
        jdbc.update("INSERT INTO users(email,password_hash,role,active,created_at) VALUES (?,?, 'OWNER',true,now())",
                "other-owner@financial.test", hash);
        jdbc.update("UPDATE patient SET first_name='Ada',last_name='Finanzas' WHERE id=?", patientId);
        jdbc.update("INSERT INTO installation_state(id,status,configured_at) VALUES (1,'COMPLETED',now())");
        Path output = Files.createTempDirectory(Path.of("/tmp/opencode"), "dc-owner-b6-real-");
        var fixture = Map.of("isolated", true, "database", "testcontainers-postgresql-16-V001-V021",
                "api", "http://127.0.0.1:" + port, "patientId", patientId,
                "emails", Map.of("owner", "owner@financial.test", "other", "other-owner@financial.test",
                        "reception", "reception@financial.test", "dentist", "dentist@financial.test", "patient", "patient@financial.test"));
        Path file = output.resolve("fixture.json");
        Files.writeString(file, mapper.writeValueAsString(fixture));
        var builder = new ProcessBuilder("npm", "run", "test:e2e", "--", "--config", "playwright.financial-recovery-real.config.ts")
                .directory(new File("../frontend")).redirectErrorStream(true).redirectOutput(output.resolve("browser.log").toFile());
        builder.environment().put("DENTALCLINIC_B6_FIXTURE", file.toString());
        builder.environment().put("DENTALCLINIC_B6_OUTPUT", output.toString());
        builder.environment().put("DENTALCLINIC_B6_PASSWORD", password);
        Process browser = builder.start();
        System.out.println("B6_REAL_OUTPUT=" + output);
        try {
            assertThat(browser.waitFor(600, TimeUnit.SECONDS)).as("Browser finished: %s", output).isTrue();
            System.out.println(Files.readString(output.resolve("browser.log")));
            assertThat(browser.exitValue()).as("Real browser evidence: %s", output).isZero();
        } finally { if (browser.isAlive()) browser.destroyForcibly(); }

        // Independent SQL reconciliation: each receipt corresponds to exactly one financial effect.
        var receiptCounts = jdbc.queryForList("SELECT operation,count(*) AS total FROM http_idempotency GROUP BY operation");
        for (String operation : new String[]{"FIN_CONFIRM_CHARGE", "FIN_REGISTER_PAYMENT", "FIN_REVERSE_PAYMENT", "FIN_CANCEL_CHARGE"}) {
            long receipts = jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE operation=?", Long.class, operation);
            assertThat(receipts).as(operation).isPositive();
            String effect = switch (operation) {
                case "FIN_CONFIRM_CHARGE" -> "SELECT count(*) FROM charge";
                case "FIN_CANCEL_CHARGE" -> "SELECT count(*) FROM charge WHERE cancelled_at IS NOT NULL";
                case "FIN_REGISTER_PAYMENT" -> "SELECT count(*) FROM financial_movement WHERE type='PAYMENT'";
                default -> "SELECT count(*) FROM financial_movement WHERE type='REVERSAL'";
            };
            assertThat(jdbc.queryForObject(effect, Long.class)).as("Effects for " + operation).isEqualTo(receipts);
            String table = operation.equals("FIN_REGISTER_PAYMENT") || operation.equals("FIN_REVERSE_PAYMENT") ? "financial_movement" : "charge";
            String actorColumn = switch (operation) {
                case "FIN_CANCEL_CHARGE" -> "cancelled_by_user_id";
                case "FIN_CONFIRM_CHARGE" -> "confirmed_by_user_id";
                default -> "performed_by_user_id";
            };
            assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency h LEFT JOIN " + table
                    + " e ON e.id=(h.response_body->'result'->>'id')::bigint WHERE h.operation=? "
                    + "AND (e.id IS NULL OR e." + actorColumn + "<>h.actor_user_id)", Long.class, operation)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(DISTINCT response_body->'result'->>'id') FROM http_idempotency WHERE operation=?",
                    Long.class, operation)).as("Unique effect per receipt: " + operation).isEqualTo(receipts);
        }
        String join = " FROM financial_intent i LEFT JOIN http_idempotency h ON h.actor_user_id=i.actor_user_id "
                + "AND h.operation=i.operation AND h.idempotency_key=i.idempotency_key ";
        assertThat(jdbc.queryForObject("SELECT count(*)" + join + "WHERE h.id IS NOT NULL AND (i.disposition<>'OPEN' OR h.request_hash<>i.request_hash)", Long.class)).isZero();
        for (String state : new String[]{"REJECTED", "ABANDONED"}) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_intent WHERE disposition=?", Long.class, state)).isPositive();
            assertThat(jdbc.queryForObject("SELECT count(*)" + join + "WHERE i.disposition=? AND h.id IS NOT NULL", Long.class, state)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT count(*)" + join + "WHERE i.disposition='OPEN' AND h.id IS NULL", Long.class)).isPositive();
        assertThat(count("appointment_checkout")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_intent WHERE command_purged_at IS NOT NULL", Long.class)).isZero();
        Files.writeString(output.resolve("sql-audit.json"), mapper.writeValueAsString(Map.of(
                "receipts", receiptCounts, "charges", count("charge"), "movements", count("financial_movement"),
                "intents", count("financial_intent"), "checkout", count("appointment_checkout"), "reconciliation", "PASS",
                "dispositions", jdbc.queryForList("SELECT disposition,count(*) AS total FROM financial_intent GROUP BY disposition"))));
    }
}

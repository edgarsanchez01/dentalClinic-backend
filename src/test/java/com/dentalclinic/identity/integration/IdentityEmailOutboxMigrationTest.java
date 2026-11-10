package com.dentalclinic.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * F8B.2 · Migración V018 sobre el DDL real.
 *
 * <p>Comprueba, en un PostgreSQL efímero tmpfs: instalación limpia de V018, upgrade V017→V018 con
 * datos y secuencias conservados, rollback transaccional del DDL, ausencia de registro en
 * {@code app_schema_version} e invariantes de motor de la tabla (índice parcial UNIQUE de una sola
 * entrega vigente, CHECK de propósito/forma). No toca la base persistente {@code dentalclinic}.
 */
class IdentityEmailOutboxMigrationTest {

    @Test
    void upgradeV017ConservaDatosYSecuenciasYV018AportaSusInvariantes() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("identity_outbox_migration")
                .withTmpFs(Map.of("/var/lib/postgresql/data", "rw")).withReuse(false)) {
            pg.start();
            assertThat(pg.getMappedPort(5432)).isNotEqualTo(5434);
            assertThat(pg.getContainerInfo().getHostConfig().getBinds()).isNullOrEmpty();

            try (Connection connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(),
                    pg.getPassword())) {
                List<Path> scripts = schemaScripts();
                for (Path script : scripts) {
                    if (script.getFileName().toString().startsWith("V018")) {
                        continue;
                    }
                    ScriptUtils.executeSqlScript(connection, new FileSystemResource(script));
                }

                try (var sql = connection.createStatement()) {
                    sql.execute("INSERT INTO users(id,email,password_hash,role) "
                            + "VALUES(101,'owner@migration.test','hash','OWNER')");
                    sql.execute("INSERT INTO users(id,email,password_hash,role) "
                            + "VALUES(201,'staff@migration.test','hash','DENTIST')");
                    sql.execute("INSERT INTO users(id,email,password_hash,role) "
                            + "VALUES(203,'reset@migration.test','hash','PATIENT')");
                    sql.execute("INSERT INTO dentist(id,user_id,first_name,last_name,phone) "
                            + "VALUES(101,101,'D','Test','123')");
                    sql.execute("INSERT INTO patient(id,first_name,last_name,phone) "
                            + "VALUES(101,'P','Test','123')");
                    sql.execute("INSERT INTO appointment(id,patient_id,dentist_id,created_by_user_id,"
                            + "start_at,end_at,duration_minutes,status,completed_at) "
                            + "VALUES(101,101,101,101,'2026-10-14T09:00Z','2026-10-14T10:00Z',60,"
                            + "'COMPLETED','2026-10-14T10:00Z')");
                    sql.execute("INSERT INTO communication(appointment_id,type,recipient_kind,"
                            + "recipient_user_id,address,idempotency_key) "
                            + "VALUES(101,'CREATED','USER',201,'staff@migration.test','existing-email')");
                }

                List<String> appointmentRows = snapshot(connection,
                        "SELECT to_jsonb(a)::text FROM appointment a ORDER BY id");
                List<String> communicationRows = snapshot(connection,
                        "SELECT to_jsonb(c)::text FROM communication c ORDER BY id");
                List<String> communicationSchema = snapshot(connection,
                        "SELECT conname||':'||pg_get_constraintdef(oid) FROM pg_constraint "
                                + "WHERE conrelid='communication'::regclass ORDER BY conname");
                List<String> sequences = snapshot(connection,
                        "SELECT sequencename||':'||coalesce(last_value::text,'unused') FROM pg_sequences "
                                + "WHERE schemaname='public' ORDER BY sequencename");

                // Rollback transaccional del DDL: V018 no deja rastro si la ventana se aborta.
                connection.setAutoCommit(false);
                ScriptUtils.executeSqlScript(connection,
                        new FileSystemResource("../db/schema/V018__identity_email_outbox.sql"));
                connection.rollback();
                assertThat(snapshot(connection, "SELECT count(*)::text FROM information_schema.tables "
                        + "WHERE table_name='identity_email_outbox'")).containsExactly("0");

                // Aplicación real.
                ScriptUtils.executeSqlScript(connection,
                        new FileSystemResource("../db/schema/V018__identity_email_outbox.sql"));
                connection.commit();

                assertThat(snapshot(connection, "SELECT to_jsonb(a)::text FROM appointment a ORDER BY id"))
                        .isEqualTo(appointmentRows);
                assertThat(snapshot(connection, "SELECT to_jsonb(c)::text FROM communication c ORDER BY id"))
                        .isEqualTo(communicationRows);
                assertThat(snapshot(connection,
                        "SELECT conname||':'||pg_get_constraintdef(oid) FROM pg_constraint "
                                + "WHERE conrelid='communication'::regclass ORDER BY conname"))
                        .isEqualTo(communicationSchema);
                assertThat(snapshot(connection, "SELECT sequencename||':'||coalesce(last_value::text,'unused') "
                        + "FROM pg_sequences WHERE schemaname='public' "
                        + "AND sequencename<>'identity_email_outbox_id_seq' ORDER BY sequencename"))
                        .isEqualTo(sequences);
                assertThat(snapshot(connection, "SELECT count(*)::text FROM identity_email_outbox"))
                        .containsExactly("0");
                assertThat(snapshot(connection, "SELECT count(*)::text FROM app_schema_version "
                        + "WHERE script_name LIKE 'V018%'")).containsExactly("0");

                // A partir de aqui cada sentencia se autoconfirma: un INSERT rechazado no aborta la
                // transaccion y las comprobaciones de invariantes siguientes siguen siendo validas.
                connection.setAutoCommit(true);
                assertEngineInvariants(connection);
            }
        }
    }

    /** Invariantes que solo garantiza el motor: una entrega vigente y forma cerrada de las columnas. */
    private void assertEngineInvariants(Connection connection) throws SQLException {
        String nonce = "A".repeat(43);
        String hash = "0".repeat(64);
        String insert = "INSERT INTO identity_email_outbox "
                + "(user_id,purpose,recipient_email,token_nonce,token_hash,idempotency_key) "
                + "VALUES(%d,'%s','dest@migration.test','%s','%s','%s')";
        try (var sql = connection.createStatement()) {
            sql.execute(insert.formatted(201, "STAFF_INVITATION", nonce, hash, "mig-1"));
        }
        // Segunda entrega vigente para la misma cuenta y propósito: el índice parcial UNIQUE la rechaza.
        assertThatThrownBy(() -> {
            try (var sql = connection.createStatement()) {
                sql.execute(insert.formatted(201, "STAFF_INVITATION", "B".repeat(43), "1".repeat(64),
                        "mig-2"));
            }
        }).isInstanceOf(SQLException.class).hasMessageContaining("identity_email_outbox_active_idx");
        // Propósito fuera del dominio cerrado.
        assertThatThrownBy(() -> {
            try (var sql = connection.createStatement()) {
                sql.execute(insert.formatted(201, "OTHER", "C".repeat(43), "2".repeat(64), "mig-3"));
            }
        }).isInstanceOf(SQLException.class).hasMessageContaining("identity_email_outbox_purpose_check");
        // Nonce de longitud inválida: lo rechaza el CHECK de forma.
        assertThatThrownBy(() -> {
            try (var sql = connection.createStatement()) {
                sql.execute(insert.formatted(203, "PASSWORD_RESET", "short", "3".repeat(64), "mig-4"));
            }
        }).isInstanceOf(SQLException.class).hasMessageContaining("identity_email_outbox_nonce_format");
        // Un propósito válido distinto sí entra (el CHECK no bloquea de más).
        try (var sql = connection.createStatement()) {
            sql.execute(insert.formatted(201, "PASSWORD_RESET", "D".repeat(43), "4".repeat(64), "mig-5"));
        }
        assertRejected(connection, insert.formatted(203, "PASSWORD_RESET", "E".repeat(43),
                "5".repeat(64), "mig-1"), "identity_email_outbox_idempotency_key");
        assertRejected(connection, insert.formatted(203, "PASSWORD_RESET", nonce,
                "5".repeat(64), "mig-6"), "identity_email_outbox_nonce_key");
        assertRejected(connection, insert.formatted(203, "PASSWORD_RESET", "E".repeat(43),
                hash, "mig-7"), "identity_email_outbox_hash_key");
        assertRejected(connection, insert.formatted(203, "PASSWORD_RESET", "E".repeat(43),
                "invalid", "mig-8"), "identity_email_outbox_hash_format");
        assertRejected(connection, "UPDATE identity_email_outbox SET status='SENT' WHERE idempotency_key='mig-1'",
                "identity_email_outbox_sent_check");
        try (var sql = connection.createStatement()) {
            sql.execute(insert.formatted(203, "PASSWORD_RESET", "E".repeat(43), "5".repeat(64), "mig-9"));
        }
        assertRejected(connection, "DELETE FROM users WHERE id=203", "identity_email_outbox_user_fkey");
    }

    private void assertRejected(Connection connection, String statement, String constraint) {
        assertThatThrownBy(() -> {
            try (var sql = connection.createStatement()) {
                sql.execute(statement);
            }
        }).isInstanceOf(SQLException.class).hasMessageContaining(constraint);
    }

    private List<Path> schemaScripts() throws Exception {
        try (var files = Files.list(Path.of("../db/schema"))) {
            return files.filter(p -> p.getFileName().toString().matches("V\\d{3}__.*\\.sql"))
                    .filter(p -> Integer.parseInt(p.getFileName().toString().substring(1, 4)) <= 18)
                    .sorted().toList();
        }
    }

    private List<String> snapshot(Connection connection, String sql) throws SQLException {
        var values = new ArrayList<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }
}

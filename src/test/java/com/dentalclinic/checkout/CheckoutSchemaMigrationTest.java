package com.dentalclinic.checkout;

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
 * Ensayo aditivo de V020 sobre PostgreSQL 16 efimero.
 *
 * <p>Aplica primero V001..V019, siembra datos historicos, comprueba que V020 se puede revertir y
 * volver a aplicar sin tocar filas, columnas ni secuencias previas, y verifica directamente las
 * constraints de unicidad, integridad referencial y checks. No se usa la base persistente.
 */
class CheckoutSchemaMigrationTest {

    @Test
    void laMigracionEsAditivaYGarantizaLaIdentidadDurableEnPostgresql() throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")
                .withDatabaseName("checkout_migration")
                .withTmpFs(Map.of("/var/lib/postgresql/data", "rw"))
                .withReuse(false)) {
            pg.start();
            assertThat(pg.getMappedPort(5432)).isNotEqualTo(5434);
            assertThat(pg.getContainerInfo().getHostConfig().getBinds()).isNullOrEmpty();
            try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(),
                    pg.getPassword())) {
                applyHistoricalSchema(c);
                seedHistoricalData(c);

                List<String> queries = tableQueries(c);
                List<List<String>> rows = snapshot(c, queries);
                List<String> sequences = strings(c, sequenceQuery(""));

                FileSystemResource migration = new FileSystemResource(
                        "../db/schema/V020__appointment_checkout.sql");

                c.setAutoCommit(false);
                ScriptUtils.executeSqlScript(c, migration);
                assertThat(tableExists(c, "appointment_checkout")).isTrue();
                c.rollback();
                assertThat(tableExists(c, "appointment_checkout")).isFalse();

                ScriptUtils.executeSqlScript(c, migration);
                c.commit();
                c.setAutoCommit(true);

                assertThat(snapshot(c, queries)).isEqualTo(rows);
                assertThat(strings(c, sequenceQuery(
                        "AND sequencename NOT LIKE 'appointment_checkout_%'"))).isEqualTo(sequences);
                assertThat(strings(c, "SELECT count(*)::text FROM app_schema_version "
                        + "WHERE script_name LIKE 'V020%'")).containsExactly("0");

                assertColumnsAndConstraints(c);
                assertUniquenessAndReferences(c);
                assertDeleteRestrictions(c);
            }
        }
    }

    private void applyHistoricalSchema(Connection c) throws Exception {
        try (var files = Files.list(Path.of("../db/schema"))) {
            var scripts = files.filter(p -> p.getFileName().toString().matches("V\\d{3}__.*\\.sql"))
                    .filter(p -> Integer.parseInt(p.getFileName().toString().substring(1, 4)) <= 19)
                    .sorted().toList();
            assertThat(scripts).hasSize(19);
            for (Path script : scripts) {
                ScriptUtils.executeSqlScript(c, new FileSystemResource(script));
            }
        }
    }

    private void seedHistoricalData(Connection c) throws Exception {
        execute(c, "INSERT INTO users(id,email,password_hash,role) VALUES"
                + "(101,'owner@checkout.test','hash','OWNER'),"
                + "(102,'patient@checkout.test','hash','PATIENT')");
        execute(c, "INSERT INTO dentist(id,user_id,first_name,last_name,phone) "
                + "VALUES(101,101,'D','Test','5550101')");
        execute(c, "INSERT INTO patient(id,user_id,first_name,last_name) "
                + "VALUES(101,102,'Paciente','ConCuenta')");
        execute(c, "INSERT INTO patient(id,first_name,last_name) "
                + "VALUES(102,'Paciente','SinCuenta')");
        execute(c, "INSERT INTO appointment(id,patient_id,dentist_id,created_by_user_id,start_at,"
                + "end_at,duration_minutes,status,occurrence_version,completed_at) VALUES"
                + "(101,101,101,101,'2026-10-14T09:00Z','2026-10-14T10:00Z',60,'COMPLETED',1,"
                + "'2026-10-14T10:00Z'),"
                + "(102,102,101,101,'2026-10-14T11:00Z','2026-10-14T12:00Z',60,'COMPLETED',1,"
                + "'2026-10-14T12:00Z'),"
                + "(103,101,101,101,'2026-10-14T13:00Z','2026-10-14T14:00Z',60,'COMPLETED',1,"
                + "'2026-10-14T14:00Z'),"
                + "(104,102,101,101,'2026-10-14T15:00Z','2026-10-14T16:00Z',60,'COMPLETED',1,"
                + "'2026-10-14T16:00Z')");
        execute(c, "INSERT INTO charge(id,patient_id,appointment_id,total_amount,confirmed_at,"
                + "confirmed_by_user_id) VALUES"
                + "(101,101,101,100.00,now(),101),"
                + "(102,102,102,80.00,now(),101),"
                + "(103,101,101,50.00,now(),101),"
                + "(104,101,103,60.00,now(),101),"
                + "(105,102,104,70.00,now(),101)");
        execute(c, "INSERT INTO financial_movement(id,charge_id,patient_id,type,direction,"
                + "payment_method,amount,performed_by_user_id,performed_at) "
                + "VALUES(101,101,101,'PAYMENT','I','CASH',40.00,101,now())");
    }

    private void assertColumnsAndConstraints(Connection c) throws Exception {
        assertThat(strings(c, "SELECT column_name FROM information_schema.columns "
                + "WHERE table_name='appointment_checkout' ORDER BY ordinal_position"))
                .containsExactly("id", "appointment_id", "occurrence_version", "charge_id",
                        "payment_movement_id", "charge_disposition", "actor_user_id", "completed_at");
        assertThat(strings(c, "SELECT column_name FROM information_schema.columns "
                + "WHERE table_name='appointment_checkout' AND column_name='updated_at'")).isEmpty();
        assertThat(strings(c, "SELECT indexname FROM pg_indexes "
                + "WHERE tablename='appointment_checkout' ORDER BY indexname"))
                .contains("appointment_checkout_appointment_key", "appointment_checkout_payment_key",
                        "appointment_checkout_charge_idx", "appointment_checkout_actor_idx");
        assertThat(constraintDef(c, "appointment_checkout_appointment_key")).contains("UNIQUE");
        assertThat(constraintDef(c, "appointment_checkout_payment_key")).contains("UNIQUE");
        assertThat(constraintDef(c, "appointment_checkout_appointment_fkey"))
                .contains("ON DELETE RESTRICT");
        assertThat(constraintDef(c, "appointment_checkout_charge_fkey"))
                .contains("ON DELETE RESTRICT");
        assertThat(constraintDef(c, "appointment_checkout_payment_fkey"))
                .contains("ON DELETE RESTRICT");
        assertThat(constraintDef(c, "appointment_checkout_actor_fkey"))
                .contains("ON DELETE RESTRICT");
        assertThat(constraintDef(c, "appointment_checkout_occurrence_check"))
                .contains("occurrence_version >= 1");
        assertThat(constraintDef(c, "appointment_checkout_disposition_check"))
                .contains("CREATED").contains("REUSED");
    }

    private void assertUniquenessAndReferences(Connection c) throws Exception {
        String valid = insert("101,1,101,101,'CREATED',101,now()");
        execute(c, valid);
        // Un segundo cierre de la misma cita se rechaza en la base.
        rejects(c, insert("101,1,103,NULL,'CREATED',101,now()"), "23505");
        // Un movimiento solo puede pertenecer a un cierre, aunque cambie la cita.
        rejects(c, insert("102,1,102,101,'CREATED',101,now()"), "23505");
        // Un cierre sin pago nuevo es valido (UNIQUE admite multiples NULL).
        execute(c, insert("102,1,102,NULL,'REUSED',101,now()"));
        // Referencias inexistentes.
        rejects(c, insert("999,1,101,NULL,'CREATED',101,now()"), "23503");
        rejects(c, insert("103,1,999,NULL,'CREATED',101,now()"), "23503");
        rejects(c, insert("999,1,999,NULL,'CREATED',101,now()"), "23503");
        rejects(c, insert("104,1,105,999,'CREATED',101,now()"), "23503");
        rejects(c, insert("104,1,105,NULL,'CREATED',999,now()"), "23503");
        // Checks cerrados.
        rejects(c, insert("104,0,105,NULL,'CREATED',101,now()"), "23514");
        rejects(c, insert("104,1,105,NULL,'OTHER',101,now()"), "23514");
        // Varios cargos de la misma cita no se restringen por el cierre.
        assertThat(strings(c, "SELECT count(*)::text FROM charge WHERE appointment_id=101"))
                .containsExactly("2");
    }

    private void assertDeleteRestrictions(Connection c) {
        rejects(c, "DELETE FROM appointment WHERE id=101", "23503");
        rejects(c, "DELETE FROM charge WHERE id=101", "23503");
        rejects(c, "DELETE FROM financial_movement WHERE id=101", "23503");
        rejects(c, "DELETE FROM users WHERE id=101", "23503");
    }

    private String insert(String values) {
        return "INSERT INTO appointment_checkout(appointment_id,occurrence_version,charge_id,"
                + "payment_movement_id,charge_disposition,actor_user_id,completed_at) VALUES("
                + values + ")";
    }

    private boolean tableExists(Connection c, String table) throws Exception {
        return !strings(c, "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema='public' AND table_name='" + table + "'").isEmpty();
    }

    private String constraintDef(Connection c, String constraint) throws Exception {
        var values = strings(c, "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conrelid='appointment_checkout'::regclass AND conname='" + constraint + "'");
        assertThat(values).hasSize(1);
        return values.get(0);
    }

    private String sequenceQuery(String filter) {
        return "SELECT sequencename||':'||coalesce(last_value::text,'unused') FROM pg_sequences "
                + "WHERE schemaname='public' " + filter + " ORDER BY sequencename";
    }

    private List<String> tableQueries(Connection c) throws Exception {
        List<String> queries = new ArrayList<>();
        for (String table : strings(c, "SELECT tablename FROM pg_tables "
                + "WHERE schemaname='public' ORDER BY tablename")) {
            List<String> columns = strings(c, "SELECT column_name FROM information_schema.columns "
                    + "WHERE table_schema='public' AND table_name='" + table
                    + "' ORDER BY ordinal_position");
            queries.add("SELECT jsonb_build_array(" + String.join(",", columns.stream()
                    .map(s -> "\"" + s + "\"").toList()) + ")::text FROM \"" + table + "\" ORDER BY 1");
        }
        return queries;
    }

    private List<List<String>> snapshot(Connection c, List<String> queries) throws Exception {
        List<List<String>> result = new ArrayList<>();
        for (String query : queries) {
            result.add(strings(c, query));
        }
        return result;
    }

    private List<String> strings(Connection c, String query) throws Exception {
        List<String> result = new ArrayList<>();
        try (var statement = c.createStatement(); var rows = statement.executeQuery(query)) {
            while (rows.next()) {
                result.add(rows.getString(1));
            }
        }
        return result;
    }

    private void execute(Connection c, String query) throws Exception {
        try (var statement = c.createStatement()) {
            statement.execute(query);
        }
    }

    private void rejects(Connection c, String query, String state) {
        assertThatThrownBy(() -> execute(c, query)).isInstanceOf(SQLException.class)
                .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo(state));
    }
}

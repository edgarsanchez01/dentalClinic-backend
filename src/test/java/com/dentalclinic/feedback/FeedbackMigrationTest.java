package com.dentalclinic.feedback;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;

class FeedbackMigrationTest {
    @Test void upgradeV016ConservaDatosYSecuenciasSinBackfillYRollbackDdl() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16").withDatabaseName("feedback_migration")
                .withTmpFs(Map.of("/var/lib/postgresql/data", "rw")).withReuse(false)) {
            pg.start();
            assertThat(pg.getMappedPort(5432)).isNotEqualTo(5434);
            assertThat(pg.getContainerInfo().getHostConfig().getBinds()).isNullOrEmpty();
            try (var connection = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                List<Path> scripts;
                try (var files = Files.list(Path.of("../db/schema"))) {
                    scripts = files.filter(p -> p.getFileName().toString().matches("V\\d{3}__.*\\.sql"))
                            .filter(p -> Integer.parseInt(p.getFileName().toString().substring(1, 4)) <= 18)
                            .sorted().toList();
                }
                for (var script : scripts) {
                    if (script.getFileName().toString().startsWith("V017")) continue;
                    ScriptUtils.executeSqlScript(connection, new FileSystemResource(script));
                }
                try (var sql = connection.createStatement()) {
                    sql.execute("INSERT INTO users(id,email,password_hash,role) VALUES(101,'synthetic@migration.test','hash','OWNER')");
                    sql.execute("INSERT INTO dentist(id,user_id,first_name,last_name,phone) VALUES(101,101,'D','Test','123')");
                    sql.execute("INSERT INTO patient(id,first_name,last_name,phone) VALUES(101,'P','Test','123')");
                    sql.execute("INSERT INTO appointment(id,patient_id,dentist_id,created_by_user_id,start_at,end_at,duration_minutes,status,completed_at) VALUES(101,101,101,101,'2026-10-14T09:00Z','2026-10-14T10:00Z',60,'COMPLETED','2026-10-14T10:00Z')");
                }
                var rows = snapshot(connection, "SELECT to_jsonb(a)::text FROM appointment a ORDER BY id");
                var sequences = snapshot(connection, "SELECT sequencename||':'||coalesce(last_value::text,'unused') FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename");
                connection.setAutoCommit(false);
                var v017 = new FileSystemResource("../db/schema/V017__patient_feedback.sql");
                ScriptUtils.executeSqlScript(connection, v017);
                connection.rollback();
                assertThat(snapshot(connection, "SELECT count(*)::text FROM information_schema.tables WHERE table_name='patient_feedback'")).containsExactly("0");
                ScriptUtils.executeSqlScript(connection, v017);
                connection.commit();
                assertThat(snapshot(connection, "SELECT to_jsonb(a)::text FROM appointment a ORDER BY id")).isEqualTo(rows);
                assertThat(snapshot(connection, "SELECT sequencename||':'||coalesce(last_value::text,'unused') FROM pg_sequences WHERE schemaname='public' AND sequencename<>'patient_feedback_id_seq' ORDER BY sequencename")).isEqualTo(sequences);
                assertThat(snapshot(connection, "SELECT count(*)::text FROM patient_feedback")).containsExactly("0");
                assertThat(snapshot(connection, "SELECT count(*)::text FROM app_schema_version WHERE script_name LIKE 'V017%'")).containsExactly("0");
            }
        }
    }

    private List<String> snapshot(Connection connection, String sql) throws SQLException {
        var values = new ArrayList<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            while (rows.next()) values.add(rows.getString(1));
        }
        return values;
    }
}

package com.dentalclinic.medicalintake;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;

class MedicalIntakeMigrationTest {
    @Test void additiveUpgradeAndRollbackPreserveAllHistoricalColumnsRowsAndSequences() throws Exception {
        try (var pg = new PostgreSQLContainer<>("postgres:16").withDatabaseName("medical_intake_migration")
                .withTmpFs(Map.of("/var/lib/postgresql/data", "rw")).withReuse(false)) {
            pg.start();
            assertThat(pg.getMappedPort(5432)).isNotEqualTo(5434);
            assertThat(pg.getContainerInfo().getHostConfig().getBinds()).isNullOrEmpty();
            try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                try (var files = Files.list(Path.of("../db/schema"))) {
                    var scripts = files.filter(p -> p.getFileName().toString().matches("V\\d{3}__.*\\.sql"))
                            .filter(p -> Integer.parseInt(p.getFileName().toString().substring(1,4)) <= 18).sorted().toList();
                    assertThat(scripts).hasSize(18);
                    for (var script : scripts) ScriptUtils.executeSqlScript(c, new FileSystemResource(script));
                }
                execute(c, "INSERT INTO users(id,email,password_hash,role) VALUES(101,'owner@migration.test','hash','OWNER'),(102,'patient@migration.test','hash','PATIENT')");
                execute(c, "INSERT INTO dentist(id,user_id,first_name,last_name,phone) VALUES(101,101,'D','Test','555')");
                execute(c, "INSERT INTO patient(id,user_id,first_name,last_name) VALUES(101,102,'Histórico','Sin teléfono')");
                execute(c, "INSERT INTO patient(id,first_name,last_name) VALUES(102,'Histórico','Sin cuenta')");
                execute(c, "INSERT INTO clinical_record(id,patient_id,medical_background,allergies,clinical_history,general_observations) VALUES(101,101,'antecedente','alergia','historia','observación')");
                execute(c, "INSERT INTO clinical_note(clinical_record_id,author_dentist_id,content,created_at) VALUES(101,101,'nota original',now())");
                execute(c, "INSERT INTO appointment(id,patient_id,dentist_id,created_by_user_id,start_at,end_at,duration_minutes,status) VALUES(101,101,101,101,'2026-10-14T09:00Z','2026-10-14T10:00Z',60,'SCHEDULED')");
                execute(c, "INSERT INTO treatment(patient_id,description,status,created_at) VALUES(101,'Tratamiento histórico','PLANNED',now())");
                execute(c, "INSERT INTO tooth_record(patient_id,tooth_fdi,condition,notes,created_at) VALUES(101,11,'HEALTHY','Dato previo',now())");
                var queries = originalTableQueries(c);
                var rows = snapshot(c, queries);
                var sequences = strings(c, sequenceQuery(""));
                var migration = new FileSystemResource("../db/schema/V019__patient_medical_intake.sql");
                c.setAutoCommit(false);
                ScriptUtils.executeSqlScript(c, migration);
                c.rollback();
                assertThat(strings(c,"SELECT count(*)::text FROM information_schema.tables WHERE table_name='patient_intake_declaration'")).containsExactly("0");
                assertThat(strings(c,"SELECT count(*)::text FROM information_schema.columns WHERE table_name='patient' AND column_name='intake_version'")).containsExactly("0");
                assertThat(snapshot(c, queries)).isEqualTo(rows);
                ScriptUtils.executeSqlScript(c, migration);
                c.commit();
                assertThat(snapshot(c, queries)).isEqualTo(rows);
                assertThat(strings(c, sequenceQuery("AND sequencename NOT LIKE 'patient_intake_%'"))).isEqualTo(sequences);
                assertThat(strings(c,"SELECT medical_onboarding_requirement||':'||medical_onboarding_progress||':'||intake_version FROM patient ORDER BY id"))
                        .containsExactly("EXEMPT_EXISTING:NOT_STARTED:0", "EXEMPT_EXISTING:NOT_STARTED:0");
                assertThat(strings(c,"SELECT count(*)::text FROM patient WHERE phone IS NULL AND birth_date IS NULL")).containsExactly("2");
                assertThat(strings(c,"SELECT count(*)::text FROM app_schema_version WHERE script_name LIKE 'V019%'")).containsExactly("0");
                // Version registration remains an operator action in the SAME authorized transaction.
                execute(c,"INSERT INTO app_schema_version(script_name,description) VALUES('V019__patient_medical_intake.sql','synthetic test only')");
                c.commit();
                assertThat(strings(c,"SELECT count(*)::text FROM app_schema_version WHERE script_name LIKE 'V019%'")).containsExactly("1");
                c.setAutoCommit(true);
                rejects(c,"UPDATE patient SET medical_onboarding_requirement='INFERRED' WHERE id=101", "23514");
                rejects(c,"UPDATE patient SET intake_version=-1 WHERE id=101", "23514");
                rejects(c,"INSERT INTO patient_intake_draft(patient_id,medical) VALUES(101,'[]')", "23514");
                rejects(c,"INSERT INTO patient_intake_draft(patient_id) VALUES(999)", "23503");
                String privacy = "INSERT INTO patient_intake_privacy(patient_id,actor_user_id,notice_version,notice_text,notice_hash,acknowledged,processing_accepted,accepted_at) VALUES(101,102,'v1','prototype','" + "a".repeat(64) + "',true,true,now())";
                execute(c,privacy);
                rejects(c,privacy,"23505");
                rejects(c,privacy.replace("101,102", "102,102").replace("'v1'", "'v2'"),"23503");
                rejects(c,"DELETE FROM patient WHERE id=101","23503");
            }
        }
    }
    private String sequenceQuery(String filter) {
        return "SELECT sequencename||':'||coalesce(last_value::text,'unused') FROM pg_sequences WHERE schemaname='public' " + filter + " ORDER BY sequencename";
    }
    private List<String> originalTableQueries(Connection c) throws Exception {
        var queries = new ArrayList<String>();
        for (String table : strings(c,"SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename")) {
            var columns = strings(c,"SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='" + table + "' ORDER BY ordinal_position");
            queries.add("SELECT jsonb_build_array(" + String.join(",", columns.stream().map(s -> "\"" + s + "\"").toList()) + ")::text FROM \"" + table + "\" ORDER BY 1");
        }
        return queries;
    }
    private List<List<String>> snapshot(Connection c,List<String> queries) throws Exception {
        var result = new ArrayList<List<String>>();
        for (String query : queries) result.add(strings(c,query));
        return result;
    }
    private List<String> strings(Connection c,String query) throws Exception {
        var result = new ArrayList<String>();
        try (var s=c.createStatement(); var rows=s.executeQuery(query)) { while(rows.next()) result.add(rows.getString(1)); }
        return result;
    }
    private void execute(Connection c,String query) throws Exception { try(var s=c.createStatement()) { s.execute(query); } }
    private void rejects(Connection c,String query,String state) {
        assertThatThrownBy(() -> execute(c,query)).isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException)e).getSQLState()).isEqualTo(state));
    }
}

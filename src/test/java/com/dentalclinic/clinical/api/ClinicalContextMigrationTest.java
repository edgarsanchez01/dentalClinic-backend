package com.dentalclinic.clinical.api;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;

/** Upgrade V012 with data, then V013; only a container owned by this test is modified. */
class ClinicalContextMigrationTest {
    @Test
    void expansionPreservaHistoriaYConstraintsRechazanContextosIncompletosOCruzados() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16").withDatabaseName("dentalclinic_1b1_upgrade")) {
            postgres.start();
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                try (var scripts = Files.list(Path.of("../db/schema"))) {
                    for (Path script : scripts.filter(path -> path.getFileName().toString().matches("V0(0[1-9]|1[0-2])__.*\\.sql")).sorted().toList()) {
                        ScriptUtils.executeSqlScript(connection, new FileSystemResource(script));
                    }
                }
                try (var sql = connection.createStatement()) {
                    sql.execute("INSERT INTO users (id,email,password_hash,role,active) VALUES (1,'upgrade@dc.test','test','OWNER',true)");
                    sql.execute("INSERT INTO dentist (id,user_id,first_name,last_name,phone) VALUES (1,1,'Autor','Real','test')");
                    sql.execute("INSERT INTO patient (id,first_name,last_name) VALUES (1,'Uno','Paciente'),(2,'Otro','Paciente')");
                    sql.execute("INSERT INTO clinical_record (id,patient_id,allergies) VALUES (1,1,'Alergia histórica')");
                    sql.execute("INSERT INTO clinical_note (id,clinical_record_id,author_dentist_id,content,created_at) VALUES (1,1,1,'Nota histórica','2026-01-01T10:00:00Z')");
                    sql.execute("INSERT INTO treatment (id,patient_id,description,created_at) VALUES (1,1,'Tratamiento histórico',now())");
                    sql.execute("INSERT INTO treatment_evolution (id,treatment_id,author_dentist_id,content,created_at) VALUES (1,1,1,'Evolución histórica','2026-01-02T10:00:00Z')");
                    sql.execute("INSERT INTO appointment (id,patient_id,dentist_id,created_by_user_id,start_at,end_at,duration_minutes) VALUES (1,1,1,1,'2026-10-13T09:00:00Z','2026-10-13T10:00:00Z',60)");
                }
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("../db/schema/V013__clinical_appointment_context.sql"));
                try (var sql = connection.createStatement()) {
                    try (var result = sql.executeQuery("SELECT content,appointment_id,appointment_occurrence_version,appointment_status_at_registration FROM clinical_note")) {
                        assertThat(result.next()).isTrue(); assertThat(result.getString(1)).isEqualTo("Nota histórica");
                        assertThat(result.getObject(2)).isNull(); assertThat(result.getObject(3)).isNull(); assertThat(result.getObject(4)).isNull();
                    }
                    try (var result = sql.executeQuery("SELECT content,appointment_id FROM treatment_evolution")) {
                        assertThat(result.next()).isTrue(); assertThat(result.getString(1)).isEqualTo("Evolución histórica"); assertThat(result.getObject(2)).isNull();
                    }
                    try (var result = sql.executeQuery("SELECT count(*) FROM appointment_occurrence_snapshot")) {
                        result.next(); assertThat(result.getLong(1)).isZero();
                    }
                    String snapshot = "INSERT INTO appointment_occurrence_snapshot VALUES (1,1,%d,1,'2026-10-13T09:00:00Z','2026-10-13T10:00:00Z','UTC',now())";
                    assertThatThrownBy(() -> sql.execute(snapshot.formatted(2))).isInstanceOf(java.sql.SQLException.class);
                    sql.execute(snapshot.formatted(1));
                    assertThatThrownBy(() -> sql.execute("UPDATE clinical_note SET appointment_id=1 WHERE id=1")).isInstanceOf(java.sql.SQLException.class);
                    assertThatThrownBy(() -> sql.execute("UPDATE clinical_note SET appointment_id=1,appointment_occurrence_version=1 WHERE id=1")).isInstanceOf(java.sql.SQLException.class);
                    assertThatThrownBy(() -> sql.execute("UPDATE clinical_note SET appointment_id=1,appointment_occurrence_version=2,appointment_status_at_registration='SCHEDULED' WHERE id=1")).isInstanceOf(java.sql.SQLException.class);
                    sql.execute("UPDATE clinical_note SET appointment_id=1,appointment_occurrence_version=1,appointment_status_at_registration='SCHEDULED' WHERE id=1");
                    assertThatThrownBy(() -> sql.execute("DELETE FROM appointment_occurrence_snapshot")).isInstanceOf(java.sql.SQLException.class);
                    assertThatThrownBy(() -> sql.execute("DELETE FROM appointment WHERE id=1")).isInstanceOf(java.sql.SQLException.class);
                }
            }
        }
    }
}

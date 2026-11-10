package com.dentalclinic.clinical.api;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Upgrade V013 con datos, despues V014; solo se modifica un contenedor propio de este test.
 *
 * <p>Comprueba que la expansion aditiva no reescribe la historia (las filas previas siguen siendo
 * raices), que las correcciones pueden apuntar a raices y que las constraints de forma, auto-referencia,
 * longitud y borrado restrictivo se aplican a nivel de base de datos.
 */
class ClinicalCorrectionMigrationTest {

    @Test
    void expansionConservaHistoriaYConstraintsProtegenLaCadenaDeCorrecciones() throws Exception {
        try (var postgres = new PostgreSQLContainer<>("postgres:16").withDatabaseName("dentalclinic_1b2_upgrade")) {
            postgres.start();
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                try (var scripts = Files.list(Path.of("../db/schema"))) {
                    for (Path script : scripts.filter(path -> path.getFileName().toString().matches("V0(0[1-9]|1[0-3])__.*\\.sql")).sorted().toList()) {
                        ScriptUtils.executeSqlScript(connection, new FileSystemResource(script));
                    }
                }
                try (var sql = connection.createStatement()) {
                    sql.execute("INSERT INTO users (id,email,password_hash,role,active) VALUES (1,'upgrade@dc.test','test','OWNER',true)");
                    sql.execute("INSERT INTO dentist (id,user_id,first_name,last_name,phone) VALUES (1,1,'Autor','Real','test')");
                    sql.execute("INSERT INTO patient (id,first_name,last_name) VALUES (1,'Uno','Paciente')");
                    sql.execute("INSERT INTO clinical_record (id,patient_id,allergies) VALUES (1,1,'Alergia historica')");
                    sql.execute("INSERT INTO clinical_note (id,clinical_record_id,author_dentist_id,content,created_at) VALUES (1,1,1,'Nota historica','2026-01-01T10:00:00Z')");
                    sql.execute("INSERT INTO treatment (id,patient_id,description,created_at) VALUES (1,1,'Tratamiento historico',now())");
                    sql.execute("INSERT INTO treatment_evolution (id,treatment_id,author_dentist_id,content,created_at) VALUES (1,1,1,'Evolucion historica','2026-01-02T10:00:00Z')");
                }
                ScriptUtils.executeSqlScript(connection, new FileSystemResource("../db/schema/V014__clinical_corrections.sql"));
                try (var sql = connection.createStatement()) {
                    // La historia no se reescribe: las filas previas siguen siendo raices.
                    try (var result = sql.executeQuery("SELECT corrects_note_id, correction_reason FROM clinical_note WHERE id = 1")) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getObject(1)).isNull();
                        assertThat(result.getObject(2)).isNull();
                    }
                    try (var result = sql.executeQuery("SELECT corrects_evolution_id, correction_reason FROM treatment_evolution WHERE id = 1")) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getObject(1)).isNull();
                        assertThat(result.getObject(2)).isNull();
                    }

                    // Correccion valida de nota: contenido completo + motivo.
                    sql.execute("INSERT INTO clinical_note (id,clinical_record_id,author_dentist_id,content,created_at,corrects_note_id,correction_reason) "
                            + "VALUES (2,1,1,'Nota corregida','2026-01-03T10:00:00Z',1,'Aclaracion del texto')");
                    sql.execute("INSERT INTO treatment_evolution (id,treatment_id,author_dentist_id,content,created_at,corrects_evolution_id,correction_reason) "
                            + "VALUES (2,1,1,'Evolucion corregida','2026-01-03T10:00:00Z',1,'Aclaracion del texto')");

                    // La raiz no puede borrarse mientras exista una correccion (ON DELETE RESTRICT).
                    assertThatThrownBy(() -> sql.execute("DELETE FROM clinical_note WHERE id = 1"))
                            .isInstanceOf(java.sql.SQLException.class);
                    assertThatThrownBy(() -> sql.execute("DELETE FROM treatment_evolution WHERE id = 1"))
                            .isInstanceOf(java.sql.SQLException.class);

                    // Forma incompleta: correccion sin motivo.
                    assertThatThrownBy(() -> sql.execute("INSERT INTO clinical_note (id,clinical_record_id,author_dentist_id,content,created_at,corrects_note_id) "
                            + "VALUES (3,1,1,'Sin motivo','2026-01-04T10:00:00Z',1)"))
                            .isInstanceOf(java.sql.SQLException.class);
                    // Auto-referencia.
                    assertThatThrownBy(() -> sql.execute("INSERT INTO clinical_note (id,clinical_record_id,author_dentist_id,content,created_at,corrects_note_id,correction_reason) "
                            + "VALUES (4,1,1,'Auto','2026-01-04T10:00:00Z',4,'x')"))
                            .isInstanceOf(java.sql.SQLException.class);
                    // Motivo vacio.
                    assertThatThrownBy(() -> sql.execute("INSERT INTO clinical_note (id,clinical_record_id,author_dentist_id,content,created_at,corrects_note_id,correction_reason) "
                            + "VALUES (5,1,1,'Vacio','2026-01-04T10:00:00Z',1,'   ')"))
                            .isInstanceOf(java.sql.SQLException.class);
                    // Motivo demasiado largo.
                    assertThatThrownBy(() -> sql.execute("INSERT INTO clinical_note (id,clinical_record_id,author_dentist_id,content,created_at,corrects_note_id,correction_reason) "
                            + "VALUES (6,1,1,'Largo','2026-01-04T10:00:00Z',1,repeat('a',1001))"))
                            .isInstanceOf(java.sql.SQLException.class);

                    try (var result = sql.executeQuery("SELECT count(*) FROM clinical_note WHERE corrects_note_id IS NOT NULL")) {
                        result.next();
                        assertThat(result.getLong(1)).isEqualTo(1L);
                    }
                }
            }
        }
    }
}

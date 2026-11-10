package com.dentalclinic.odontogram.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;

/** E1 solamente: JDBC directo a PostgreSQL efimero, sin contexto Spring ni datasource del operador. */
class OdontogramHistoryMigrationTest {
    private static final String DATABASE = "dentalclinic_1b3_e1";
    private static final Path SCHEMA = Path.of("../db/schema");
    private static final String V015 = "V015__odontogram_history_context.sql";
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName(DATABASE)
            .withTmpFs(Map.of("/var/lib/postgresql/data", "rw"))
            .withReuse(false);
    private static final List<String> NEW_TABLES = List.of(
            "tooth_notes_history", "tooth_surface_notes_history", "tooth_treatment_history");
    private static final List<String> INDEXES = List.of(
            "tooth_history_attention_idx", "tooth_surface_history_attention_idx",
            "tooth_notes_history_record_idx", "tooth_notes_history_attention_idx",
            "tooth_surface_notes_history_record_idx", "tooth_surface_notes_history_attention_idx",
            "tooth_treatment_history_record_idx", "tooth_treatment_history_treatment_idx",
            "tooth_treatment_history_attention_idx");
    private Connection connection;

    @BeforeAll
    static void startOnlyEphemeralPostgres() {
        POSTGRES.start();
        // Antes de abrir JDBC o ejecutar SQL: URL generada por ESTE contenedor y ningun bind.
        assertThat(POSTGRES.getDatabaseName()).isEqualTo(DATABASE);
        assertThat(POSTGRES.getJdbcUrl().split("\\?", 2)[0]).endsWith("/" + DATABASE);
        assertThat(POSTGRES.getMappedPort(5432)).isNotEqualTo(5434);
        var hostConfig = POSTGRES.getContainerInfo().getHostConfig();
        assertThat(hostConfig.getBinds()).isNullOrEmpty();
        assertThat(hostConfig.getTmpFs()).containsKey("/var/lib/postgresql/data");
        // Un volumen/bind tiene un Source de disco; tmpfs no tiene origen persistente.
        assertThat(POSTGRES.getContainerInfo().getMounts())
                .allSatisfy(mount -> assertThat(mount.getSource()).isNullOrEmpty());
        System.out.println("E1 isolation verified: database=" + DATABASE
                + ", mappedPort=" + POSTGRES.getMappedPort(5432) + ", storage=tmpfs, reuse=false");
    }

    @AfterAll
    static void stopOnlyOwnedContainer() {
        POSTGRES.stop();
    }

    @BeforeEach
    void createV014FixtureOnlyInsideEphemeralDatabase() throws Exception {
        connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        assertThat(strings("SELECT current_database()")).containsExactly(DATABASE);
        resetEphemeralSchema();
        installThrough(14);
        seedLegacyData();
    }

    @AfterEach
    void closeConnection() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    void instalacionLimpiaV001AV015TieneEsquemaSinDatosFicticios() throws Exception {
        resetEphemeralSchema();
        installThrough(15);
        assertSchema();
        for (String table : strings("SELECT tablename FROM pg_tables WHERE schemaname='public'")) {
            // V007-V011 registran sus scripts; no son datos clinicos ni un backfill de V015.
            if (!table.equals("app_schema_version")) assertThat(count(table)).as(table).isZero();
        }
        assertThat(strings("SELECT script_name FROM app_schema_version WHERE script_name LIKE 'V015%'")).isEmpty();
    }

    @Test
    void upgradeConservaTodasLasFilasColumnasYSecuenciasV014SinBackfill() throws Exception {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        Map<String, List<String>> before = new LinkedHashMap<>();
        for (String table : strings("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename")) {
            columns.put(table, columns(table));
            before.put(table, rows(table, columns.get(table)));
        }
        Map<String, List<String>> sequences = new LinkedHashMap<>();
        for (String sequence : strings("SELECT sequencename FROM pg_sequences WHERE schemaname='public'")) {
            sequences.put(sequence, strings("SELECT last_value || ':' || is_called FROM " + sequence));
        }
        upgrade();
        assertSchema();
        for (String table : before.keySet()) {
            assertThat(rows(table, columns.get(table))).as("legacy table %s", table).isEqualTo(before.get(table));
        }
        for (String sequence : sequences.keySet()) {
            assertThat(strings("SELECT last_value || ':' || is_called FROM " + sequence))
                    .as(sequence).isEqualTo(sequences.get(sequence));
        }
        for (String table : NEW_TABLES) assertThat(count(table)).as("no backfill: %s", table).isZero();
        for (String table : List.of("tooth_history", "tooth_surface_history")) {
            assertThat(strings("SELECT count(*) FROM " + table
                    + " WHERE appointment_id IS NOT NULL OR appointment_occurrence_version IS NOT NULL"
                    + " OR appointment_status_at_registration IS NOT NULL")).containsExactly("0");
        }
        assertThat(count("appointment_occurrence_snapshot")).isEqualTo(2);
    }

    static Stream<String> eventTables() {
        return Stream.of("tooth_history", "tooth_surface_history", "tooth_notes_history",
                "tooth_surface_notes_history", "tooth_treatment_history");
    }

    @ParameterizedTest(name = "{0}: contexto general y cuatro estados observados")
    @MethodSource("eventTables")
    void contextoOpcionalYCompletoCompatibleConAutorUsuarioSinPerfil(String table) throws Exception {
        upgrade();
        execute(event(table, "NULL,NULL,NULL", "2", "2"));
        for (String status : List.of("SCHEDULED", "COMPLETED", "CANCELLED", "NO_SHOW")) {
            execute(event(table, "1,2,'" + status + "'", "2", "2"));
        }
        assertThat(strings("SELECT count(*) FROM " + table + " WHERE actor_user_id=2")).containsExactly("5");
        assertThat(strings("SELECT count(*) FROM dentist WHERE user_id=2")).containsExactly("0");
    }

    @ParameterizedTest(name = "{0}: bloque de contexto completo o ausente")
    @MethodSource("eventTables")
    void rechazaTodosLosBloquesParcialesVersionesYEstadosInvalidos(String table) throws Exception {
        upgrade();
        for (int mask = 1; mask < 7; mask++) {
            String context = ((mask & 1) != 0 ? "1" : "NULL") + ","
                    + ((mask & 2) != 0 ? "2" : "NULL") + ","
                    + ((mask & 4) != 0 ? "'SCHEDULED'" : "NULL");
            rejects(event(table, context, "2", "2"), "23514");
        }
        rejects(event(table, "1,0,'SCHEDULED'", "2", "2"), "23514");
        rejects(event(table, "1,-1,'SCHEDULED'", "2", "2"), "23514");
        rejects(event(table, "1,2,'CONFIRMED'", "2", "2"), "23514");
        rejects(event(table, "1,2,''", "2", "2"), "23514");
    }

    @ParameterizedTest(name = "{0}: referencias y borrado restrictivo")
    @MethodSource("eventTables")
    void exigeReferenciasRealesYProtegeContextoYAutor(String table) throws Exception {
        upgrade();
        rejects(event(table, "NULL,NULL,NULL", "999", "2"), "23503");
        rejects(event(table, "NULL,NULL,NULL", "2", "999"), "23503");
        rejects(event(table, "999,2,'SCHEDULED'", "2", "2"), "23503");
        rejects(event(table, "1,3,'SCHEDULED'", "2", "2"), "23503");
        rejects(event(table, "NULL,NULL,NULL", "2", "NULL"), "23502");
        execute(event(table, "1,2,'COMPLETED'", "2", "2"));
        rejects("DELETE FROM appointment_occurrence_snapshot WHERE appointment_id=1 AND occurrence_version=2", "23503");
        rejects("DELETE FROM users WHERE id=2", "23503");
        String parent = table.contains("surface") ? "tooth_surface_record" : "tooth_record";
        rejects("DELETE FROM " + parent + " WHERE id=2", "23503");
        rejects(event(table, "NULL,NULL,NULL", "2", "2").replace("'2026-02-01T12:00:00Z'", "NULL"), "23502");
    }

    @ParameterizedTest(name = "{0}: observaciones, NULL y limite de 4000")
    @ValueSource(strings = {"tooth_notes_history", "tooth_surface_notes_history"})
    void observacionesPermitenAltaYLimpiarPeroNoNoopNiTextoExcesivo(String table) throws Exception {
        upgrade();
        execute(notes(table, "NULL", "repeat('a',4000)"));
        execute(notes(table, "repeat('a',4000)", "NULL"));
        rejects(notes(table, "NULL", "repeat('a',4001)"), "23514");
        rejects(notes(table, "repeat('a',4001)", "NULL"), "23514");
        rejects(notes(table, "NULL", "NULL"), "23514");
        rejects(notes(table, "'igual'", "'igual'"), "23514");
        assertThat(count(table)).isEqualTo(2);
        assertThat(strings("SELECT new_notes IS NULL FROM " + table + " ORDER BY id")).containsExactly("f", "t");
    }

    @Test
    void vinculosAdmitenAddRemoveYReasociacionSinDependerDelVinculoVigente() throws Exception {
        upgrade();
        String add = event("tooth_treatment_history", "NULL,NULL,NULL", "2", "2");
        execute(add);
        execute(add.replace("'ADD'", "'REMOVE'"));
        execute(add);
        rejects(add.replace("'ADD'", "'UPDATE'"), "23514");
        rejects(add.replace("'ADD'", "NULL"), "23502");
        rejects(add.replace("2,'ADD'", "999,'ADD'"), "23503");
        rejects("DELETE FROM treatment WHERE id=2", "23503");
        assertThat(count("treatment_tooth")).isEqualTo(1); // Solo el vinculo historico del fixture.
        assertThat(strings("SELECT change_kind FROM tooth_treatment_history ORDER BY id"))
                .containsExactly("ADD", "REMOVE", "ADD");
    }

    @Test
    void falloAntesDelCommitRevierteTodoElDdlV015YConservaElFixture() throws Exception {
        List<String> before = rows("tooth_history", columns("tooth_history"));
        connection.setAutoCommit(false);
        try {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(SCHEMA.resolve(V015)));
            rejects(event("tooth_notes_history", "1,0,'SCHEDULED'", "2", "2"), "23514");
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
        assertThat(columns("tooth_history")).doesNotContain("appointment_id");
        assertThat(strings("SELECT tablename FROM pg_tables WHERE schemaname='public'"))
                .doesNotContainAnyElementsOf(NEW_TABLES);
        assertThat(rows("tooth_history", columns("tooth_history"))).isEqualTo(before);
    }

    private void assertSchema() throws Exception {
        assertThat(strings("SELECT tablename FROM pg_tables WHERE schemaname='public'"))
                .containsAll(NEW_TABLES);
        assertThat(strings("SELECT indexname FROM pg_indexes WHERE schemaname='public'"))
                .containsAll(INDEXES);
        for (String index : INDEXES) {
            String expected;
            if (index.endsWith("_attention_idx")) {
                expected = "appointment_id,appointment_occurrence_version,changed_at,id";
            } else if (index.equals("tooth_treatment_history_treatment_idx")) {
                expected = "treatment_id";
            } else {
                expected = (index.contains("surface") ? "tooth_surface_record_id" : "tooth_record_id")
                        + ",changed_at,id";
            }
            assertThat(strings("SELECT string_agg(a.attname,',' ORDER BY k.position) FROM pg_index i"
                    + " CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY k(attnum,position)"
                    + " JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=k.attnum"
                    + " WHERE i.indexrelid='" + index + "'::regclass")).as(index).containsExactly(expected);
        }
        for (String table : eventTables().toList()) {
            assertThat(columns(table)).contains("appointment_id", "appointment_occurrence_version",
                    "appointment_status_at_registration");
            assertThat(strings("SELECT is_nullable || ':' || coalesce(column_default,'none')"
                    + " FROM information_schema.columns WHERE table_schema='public' AND table_name='"
                    + table + "' AND column_name IN ('appointment_id','appointment_occurrence_version',"
                    + "'appointment_status_at_registration') ORDER BY column_name"))
                    .containsExactly("YES:none", "YES:none", "YES:none");
            assertThat(strings("SELECT confdeltype::text FROM pg_constraint WHERE conrelid='"
                    + table + "'::regclass AND conname='" + table + "_context_fk'"))
                    .containsExactly("r");
            assertThat(strings("SELECT pg_get_expr(indpred,indrelid) FROM pg_index WHERE indexrelid='"
                    + table + "_attention_idx'::regclass")).containsExactly("(appointment_id IS NOT NULL)");
            for (String column : List.of("actor_user_id", "changed_at")) {
                assertThat(strings("SELECT is_nullable FROM information_schema.columns WHERE table_schema='public'"
                        + " AND table_name='" + table + "' AND column_name='" + column + "'"))
                        .containsExactly("NO");
            }
        }
        for (String table : NEW_TABLES) {
            assertThat(strings("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE contype='p'"
                    + " AND conrelid='" + table + "'::regclass")).containsExactly("PRIMARY KEY (id)");
            assertThat(strings("SELECT confdeltype::text FROM pg_constraint WHERE contype='f'"
                    + " AND conrelid='" + table + "'::regclass")).containsOnly("r");
        }
        assertThat(strings("SELECT tgname FROM pg_trigger WHERE NOT tgisinternal AND tgrelid IN ("
                + "'tooth_history'::regclass,'tooth_surface_history'::regclass,'tooth_notes_history'::regclass,"
                + "'tooth_surface_notes_history'::regclass,'tooth_treatment_history'::regclass)")).isEmpty();
    }

    private void resetEphemeralSchema() throws Exception {
        assertThat(strings("SELECT current_database()")).containsExactly(DATABASE);
        execute("DROP SCHEMA public CASCADE");
        execute("CREATE SCHEMA public");
    }

    private void installThrough(int version) throws Exception {
        List<Path> scripts;
        try (var files = Files.list(SCHEMA)) {
            scripts = files.filter(p -> p.getFileName().toString().matches("V\\d{3}__.*\\.sql"))
                    .filter(p -> Integer.parseInt(p.getFileName().toString().substring(1, 4)) <= version)
                    .sorted().toList();
        }
        assertThat(scripts).hasSize(version); // No instalacion parcial silenciosa.
        for (Path script : scripts) apply(script);
    }

    private void upgrade() throws Exception {
        apply(SCHEMA.resolve(V015));
    }

    private void apply(Path script) throws Exception {
        connection.setAutoCommit(false);
        try {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(script));
            connection.commit();
        } catch (Exception failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void seedLegacyData() throws Exception {
        execute("INSERT INTO users(id,email,password_hash,role,active) VALUES "
                + "(1,'e1-owner@dc.test','test','OWNER',true),(2,'e1-actor@dc.test','test','DENTIST',true)");
        execute("INSERT INTO dentist(id,user_id,first_name,last_name,phone) VALUES (1,1,'Autor','Historico','test')");
        execute("INSERT INTO patient(id,first_name,last_name) VALUES (1,'Paciente','Historico')");
        execute("INSERT INTO appointment(id,patient_id,dentist_id,created_by_user_id,start_at,end_at,duration_minutes,"
                + "occurrence_version) VALUES (1,1,1,1,'2026-01-01T09:00:00Z','2026-01-01T10:00:00Z',60,2)");
        execute("INSERT INTO appointment_occurrence_snapshot VALUES "
                + "(1,1,1,1,'2026-01-01T09:00:00Z','2026-01-01T10:00:00Z','UTC','2026-01-01T09:15:00Z'),"
                + "(1,2,1,1,'2026-02-01T09:00:00Z','2026-02-01T10:00:00Z','UTC','2026-02-01T09:15:00Z')");
        execute("INSERT INTO treatment(id,patient_id,description,created_at) VALUES "
                + "(1,1,'Tratamiento historico','2026-01-01T10:00:00Z'),(2,1,'Tratamiento sin vinculo','2026-01-02T10:00:00Z')");
        execute("INSERT INTO clinical_record(id,patient_id,allergies) VALUES (1,1,'Alergia historica')");
        execute("INSERT INTO clinical_note(id,clinical_record_id,author_dentist_id,content,created_at,"
                + "appointment_id,appointment_occurrence_version,appointment_status_at_registration) VALUES "
                + "(1,1,1,'Nota contextual previa','2026-01-01T10:00:00Z',1,1,'SCHEDULED')");
        execute("INSERT INTO clinical_note(id,clinical_record_id,author_dentist_id,content,created_at,"
                + "corrects_note_id,correction_reason,appointment_id,appointment_occurrence_version,"
                + "appointment_status_at_registration) VALUES "
                + "(2,1,1,'Correccion previa','2026-01-02T10:00:00Z',1,'Aclaracion',1,1,'SCHEDULED')");
        execute("INSERT INTO treatment_evolution(id,treatment_id,author_dentist_id,content,created_at) "
                + "VALUES (1,1,1,'Evolucion previa','2026-01-01T10:00:00Z')");
        execute("INSERT INTO tooth_record(id,patient_id,tooth_fdi,condition,notes,created_at) VALUES "
                + "(1,1,16,'CROWN','Observaciones previas sin historial','2026-01-01T10:00:00Z'),"
                + "(2,1,21,'HEALTHY',NULL,'2026-01-01T10:00:00Z')");
        execute("INSERT INTO tooth_surface_record(id,tooth_record_id,surface,condition,notes,created_at) VALUES "
                + "(1,1,'OCCLUSAL','CARIES','Superficie previa','2026-01-01T10:00:00Z'),"
                + "(2,1,'MESIAL','HEALTHY',NULL,'2026-01-01T10:00:00Z')");
        execute("INSERT INTO tooth_history(id,tooth_record_id,previous_condition,new_condition,actor_user_id,changed_at) VALUES "
                + "(100,1,NULL,'CARIES',1,'2026-01-01T10:00:00Z'),(101,1,'CARIES','CROWN',1,'2026-01-02T10:00:00Z')");
        execute("INSERT INTO tooth_surface_history(id,tooth_surface_record_id,previous_condition,new_condition,actor_user_id,changed_at) "
                + "VALUES (100,1,NULL,'CARIES',1,'2026-01-01T10:00:00Z')");
        execute("INSERT INTO treatment_tooth(id,treatment_id,tooth_record_id,created_at) "
                + "VALUES (1,1,1,'2026-01-01T10:00:00Z')");
    }

    private String event(String table, String context, String parent, String actor) {
        String parentColumn = table.contains("surface") ? "tooth_surface_record_id" : "tooth_record_id";
        String columns;
        String values;
        if (table.endsWith("notes_history")) {
            columns = "previous_notes,new_notes";
            values = "NULL,'Observacion registrada'";
        } else if (table.equals("tooth_treatment_history")) {
            columns = "treatment_id,change_kind";
            values = "2,'ADD'";
        } else {
            columns = "previous_condition,new_condition";
            values = "'HEALTHY','CARIES'";
        }
        return "INSERT INTO " + table + " (" + parentColumn + "," + columns
                + ",actor_user_id,changed_at,appointment_id,appointment_occurrence_version,appointment_status_at_registration)"
                + " VALUES (" + parent + "," + values + "," + actor + ",'2026-02-01T12:00:00Z'," + context + ")";
    }

    private String notes(String table, String previous, String next) {
        return event(table, "NULL,NULL,NULL", "2", "2")
                .replace("NULL,'Observacion registrada'", previous + "," + next);
    }

    private void rejects(String sql, String sqlState) {
        assertThatThrownBy(() -> execute(sql)).as(sql)
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo(sqlState));
    }

    private void execute(String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long count(String table) throws SQLException {
        return Long.parseLong(strings("SELECT count(*) FROM " + table).getFirst());
    }

    private List<String> columns(String table) throws SQLException {
        return strings("SELECT column_name FROM information_schema.columns WHERE table_schema='public'"
                + " AND table_name='" + table + "' ORDER BY ordinal_position");
    }

    private List<String> rows(String table, List<String> columns) throws SQLException {
        String projection = columns.stream().map(column -> "\"" + column + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        return strings("SELECT to_jsonb(legacy_row)::text FROM (SELECT " + projection + " FROM " + table
                + ") legacy_row ORDER BY 1");
    }

    private List<String> strings(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            while (result.next()) values.add(result.getString(1));
        }
        return values;
    }
}

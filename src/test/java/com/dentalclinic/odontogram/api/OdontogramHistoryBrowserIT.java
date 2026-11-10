package com.dentalclinic.odontogram.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.appointment.integration.AppointmentIntegrationTestSupport;

/**
 * Validación integrada 1B.3 previa a E5: navegador real (SPA E3) contra este backend E2 y una base
 * PostgreSQL 16 efímera de Testcontainers con el DDL real V001–V015. Sin intercepción de red.
 *
 * <p>Opt-in explícito: {@code mvn -Dtest=OdontogramHistoryBrowserIT test}. No usa la base persistente
 * ({@code :5434/dentalclinic}) ni aplica V015 fuera de la base efímera.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:16:///dentalclinic_odontogram_browser?TC_TMPFS=/var/lib/postgresql/data:rw",
        "dentalclinic.security.cors.allowed-origins=http://127.0.0.1:5188"
})
class OdontogramHistoryBrowserIT extends AppointmentIntegrationTestSupport {
    @LocalServerPort int port;

    @Test
    void spaBackendYPostgresRealesSinIntercepciones() throws Exception {
        assertThat(jdbcTemplate.queryForObject("SELECT current_database()", String.class)).isEqualTo("test");
        // V001–V015 aplicadas a la base efímera: comprueba los objetos específicos de V015.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.tooth_notes_history') IS NOT NULL", Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.tooth_surface_notes_history') IS NOT NULL", Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.tooth_treatment_history') IS NOT NULL", Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name='tooth_history' AND column_name='appointment_occurrence_version'",
                String.class)).isEqualTo("appointment_occurrence_version");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname IN ('tooth_history_attention_idx',"
                        + "'tooth_surface_history_attention_idx','tooth_notes_history_attention_idx')", Long.class))
                .isEqualTo(3L);
        System.out.println("1B.3 esquema efímero V015 verificado sobre "
                + jdbcTemplate.queryForObject("SELECT current_database()", String.class));
        jdbcTemplate.update("INSERT INTO installation_state (id,status,configured_at) VALUES (1,'COMPLETED',now())");
        long appointment = appointmentRepository.saveAndFlush(Appointment.create(patient.getId(), dentist.getId(),
                owner.getId(), at(MARTES, 9, 0), at(MARTES, 10, 0), 60, "Fixture 1B.3", null)).getId();

        Path log = Files.createTempFile(Path.of("/tmp/opencode"), "dentalclinic-1b3-browser-", ".log");
        var builder = new ProcessBuilder("npm", "run", "test:e2e", "--", "--config", "playwright.odontogram-real.config.ts")
                .directory(new File("../frontend")).redirectErrorStream(true).redirectOutput(log.toFile());
        var env = builder.environment();
        env.put("DENTALCLINIC_REAL_API_URL", "http://127.0.0.1:" + port);
        env.put("DENTALCLINIC_PATIENT_ID", String.valueOf(patient.getId()));
        env.put("DENTALCLINIC_DENTIST_EMAIL", dentistUser.getEmail());
        env.put("DENTALCLINIC_PATIENT_EMAIL", patientUser.getEmail());
        env.put("DENTALCLINIC_RECEPTIONIST_EMAIL", receptionist.getEmail());
        env.put("DENTALCLINIC_TEST_PASSWORD", PASSWORD);
        env.put("DENTALCLINIC_APPOINTMENT_ID", String.valueOf(appointment));
        env.put("DENTALCLINIC_OCCURRENCE_VERSION", "1");
        Process browser = builder.start();
        try {
            assertThat(browser.waitFor(300, TimeUnit.SECONDS)).as("Playwright completó").isTrue();
            System.out.println(Files.readString(log));
            assertThat(browser.exitValue()).as("Recorridos reales del navegador").isZero();
        } finally {
            if (browser.isAlive()) browser.destroyForcibly();
        }

        // La SPA escribió contra el backend real y este persistió en PostgreSQL: se lee directo de la base.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT condition FROM tooth_record WHERE patient_id = ? AND tooth_fdi = 48", String.class, patient.getId()))
                .isEqualTo("CARIES");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT condition FROM tooth_record WHERE patient_id = ? AND tooth_fdi = 31", String.class, patient.getId()))
                .isEqualTo("FRACTURED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tooth_record WHERE patient_id = ?", Long.class, patient.getId())).isEqualTo(2L);
        assertThat(countRows("tooth_history")).isGreaterThanOrEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tooth_surface_record WHERE condition = 'RESTORED'", Long.class)).isEqualTo(1L);
        assertThat(countRows("tooth_surface_history")).isGreaterThanOrEqualTo(1L);
    }
}

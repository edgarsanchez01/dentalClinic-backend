package com.dentalclinic.appointment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** Explicit opt-in: mvn -Dtest=ClinicalTraceBrowserIT test. Own PostgreSQL, actual HTTP and browser. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:16:///dentalclinic_clinical_browser",
        "dentalclinic.security.cors.allowed-origins=http://127.0.0.1:5187"
})
class ClinicalTraceBrowserIT extends AppointmentIntegrationTestSupport {
    @LocalServerPort int port;

    @Test
    void navegadorFrontendBackendYPostgresSinIntercepciones() throws Exception {
        jdbcTemplate.update("INSERT INTO installation_state (id,status,configured_at) VALUES (1,'COMPLETED',now())");
        long a = createAppointment(9); long b = createAppointment(13);
        mockMvc.perform(post("/api/patients/" + patient.getId() + "/clinical/treatments")
                .header("Authorization", bearer(loginToken(dentistUser))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"Tratamiento longitudinal real\"}"))
                .andExpect(status().isCreated());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM appointment_occurrence_snapshot", Long.class)).isZero();
        Path log = Files.createTempFile(Path.of("/tmp/opencode"), "dentalclinic-1b1-browser-", ".log");
        var builder = new ProcessBuilder("npm", "run", "test:e2e", "--", "--config", "playwright.clinical-real.config.ts")
                .directory(new File("../frontend")).redirectErrorStream(true).redirectOutput(log.toFile());
        var env = builder.environment();
        env.put("DENTALCLINIC_REAL_API_URL", "http://127.0.0.1:" + port);
        env.put("DENTALCLINIC_DENTIST_EMAIL", dentistUser.getEmail());
        env.put("DENTALCLINIC_TEST_PASSWORD", PASSWORD);
        env.put("DENTALCLINIC_OWNER_TOKEN", loginToken(owner));
        env.put("DENTALCLINIC_APPOINTMENT_A", Long.toString(a));
        env.put("DENTALCLINIC_APPOINTMENT_B", Long.toString(b));
        env.put("DENTALCLINIC_START_A", at(MARTES, 11, 0).toString());
        env.put("DENTALCLINIC_END_A", at(MARTES, 12, 0).toString());
        env.put("DENTALCLINIC_START_B", at(MARTES, 15, 0).toString());
        env.put("DENTALCLINIC_END_B", at(MARTES, 16, 0).toString());
        Process browser = builder.start();
        try {
            assertThat(browser.waitFor(180, TimeUnit.SECONDS)).as("Playwright completed").isTrue();
            System.out.println(Files.readString(log));
            assertThat(browser.exitValue()).as("Real browser journeys").isZero();
        } finally {
            if (browser.isAlive()) browser.destroyForcibly();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM appointment_occurrence_snapshot", Long.class)).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM clinical_note WHERE appointment_status_at_registration='COMPLETED'", Long.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM clinical_note WHERE appointment_id IS NULL", Long.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM treatment_evolution", Long.class)).isEqualTo(2);
    }

    private long createAppointment(int hour) throws Exception {
        var result = mockMvc.perform(post("/api/appointments").header("Authorization", bearer(loginToken(owner)))
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(createBody(patient.getId(), dentist.getId(), at(MARTES, hour, 0), at(MARTES, hour + 1, 0))))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }
}

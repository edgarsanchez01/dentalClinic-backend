package com.dentalclinic.appointment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** BUILD 2 opt-in browser harness only; no production changes or persistent database access. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:16:///dentalclinic_ux05c_browser?TC_TMPFS=/var/lib/postgresql/data:rw",
        "dentalclinic.security.cors.allowed-origins=http://127.0.0.1:5198"
})
class SeparatedCareBrowserIT extends AppointmentIntegrationTestSupport {
    @LocalServerPort int port;

    @Test void dentistReceptionOwnerThroughActualSpaHttpAndEphemeralPostgres() throws Exception {
        jdbcTemplate.update("INSERT INTO installation_state(id,status,configured_at) VALUES (1,'COMPLETED',now())");
        var ids = new ArrayList<Long>();
        for (int hour = 9; hour < 13; hour++) {
            var created = mockMvc.perform(post("/api/appointments")
                    .header("Authorization", bearer(loginToken(owner)))
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(patient.getId(), dentist.getId(), at(MARTES, hour, 0), at(MARTES, hour + 1, 0))))
                    .andExpect(status().isCreated()).andReturn();
            long id = objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asLong();
            jdbcTemplate.update("UPDATE appointment SET service_reference_price=500 WHERE id=?", id);
            ids.add(id);
        }
        Path output = Files.createTempDirectory(Path.of("/tmp/opencode"), "ux05c-real-");
        Path fixture = output.resolve("fixture.json");
        Files.writeString(fixture, objectMapper.writeValueAsString(Map.of(
                "isolated", true, "database", "jdbc:tc:postgresql:16:///dentalclinic_ux05c_browser?TC_TMPFS=/var/lib/postgresql/data:rw",
                "api", "http://127.0.0.1:" + port, "ids", ids, "patientId", patient.getId(), "dentistId", dentist.getId())));
        var builder = new ProcessBuilder("npm", "run", "test:e2e", "--", "--config", "playwright.care-real.config.ts")
                .directory(new File("../frontend")).redirectErrorStream(true).redirectOutput(output.resolve("browser.log").toFile());
        var env = builder.environment();
        env.put("DENTALCLINIC_CARE_FIXTURE", fixture.toString());
        env.put("DENTALCLINIC_CARE_OUTPUT", output.toString());
        env.put("DENTALCLINIC_DENTIST_EMAIL", dentistUser.getEmail());
        env.put("DENTALCLINIC_RECEPTION_EMAIL", receptionist.getEmail());
        env.put("DENTALCLINIC_OWNER_EMAIL", owner.getEmail());
        env.put("DENTALCLINIC_TEST_PASSWORD", PASSWORD);
        Process browser = builder.start();
        try {
            assertThat(browser.waitFor(300, TimeUnit.SECONDS)).as("Browser completed: %s", output).isTrue();
            System.out.println("UX05C_REAL_OUTPUT=" + output);
            System.out.println(Files.readString(output.resolve("browser.log")));
            assertThat(browser.exitValue()).as("Actual SPA journey; evidence: %s", output).isZero();
        } finally { if (browser.isAlive()) browser.destroyForcibly(); }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM appointment WHERE status='COMPLETED'", Long.class)).isEqualTo(4);
        assertThat(countRows("charge")).isEqualTo(1);
        assertThat(countRows("financial_movement")).isEqualTo(2);
        assertThat(countRows("appointment_checkout")).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM appointment_event WHERE event_type='APPOINTMENT_COMPLETED'", Long.class)).isEqualTo(4);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM clinical_note", Long.class)).isEqualTo(1);
        assertThat(countRows("treatment")).isEqualTo(1);
        assertThat(countRows("treatment_evolution")).isEqualTo(1);
        assertThat(countRows("tooth_record")).isEqualTo(1);
    }
}

package com.dentalclinic.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.File;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import com.dentalclinic.appointment.integration.*;
import com.dentalclinic.appointment.application.CommunicationDispatcher;
import com.dentalclinic.appointment.domain.Appointment;

/** Opt-in: HTTP real, emisor falso, navegador real y PostgreSQL16 V001–V017. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:postgresql:16:///dentalclinic_feedback_browser",
    "dentalclinic.feedback.public-base-url=http://127.0.0.1:5196",
    "dentalclinic.security.cors.allowed-origins=http://127.0.0.1:5196"
})
class FeedbackBrowserIT extends AppointmentIntegrationTestSupport {
    @LocalServerPort int port;
    @Autowired RecordingEmailSender sender;
    @Autowired CommunicationDispatcher dispatcher;

    @Test void recorridoCompletoEnNavegadorSinMocksHttp() throws Exception {
        jdbcTemplate.update("INSERT INTO installation_state(id,status,configured_at) VALUES(1,'COMPLETED',now())");
        Long id = appointmentRepository.saveAndFlush(Appointment.create(patient.getId(), dentist.getId(), owner.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0), 60, "Clínica privada", null)).getId();
        var api = RestClient.create("http://127.0.0.1:" + port);
        api.post().uri("/api/appointments/" + id + "/complete")
                .header("Authorization", bearer(loginToken(dentistUser)))
                .header("Idempotency-Key",java.util.UUID.randomUUID().toString())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(java.util.Map.of("expectedOccurrenceVersion",1)).retrieve().toBodilessEntity();
        sender.reset(); assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        String token = sender.messages().getFirst().body().split("/feedback/")[1].split("\\s")[0];
        assertThat(dispatcher.dispatchOnce()).isZero();
        Path log = Files.createTempFile(Path.of("/tmp/opencode"), "feedback-browser-", ".log");
        var builder = new ProcessBuilder("npm", "run", "test:e2e", "--", "--config", "playwright.feedback-real.config.ts")
                .directory(new File("../frontend")).redirectErrorStream(true).redirectOutput(log.toFile());
        var env = builder.environment();
        env.put("DENTALCLINIC_REAL_API_URL", "http://127.0.0.1:" + port);
        env.put("FEEDBACK_TEST_TOKEN", token);
        env.put("FEEDBACK_TEST_OWNER", owner.getEmail());
        env.put("FEEDBACK_TEST_DENTIST", dentistUser.getEmail());
        env.put("FEEDBACK_TEST_RECEPTION", receptionist.getEmail());
        env.put("FEEDBACK_TEST_PATIENT", patientUser.getEmail());
        env.put("FEEDBACK_TEST_OTHER_DENTIST", newUser(com.dentalclinic.identity.domain.Role.DENTIST).getEmail());
        env.put("FEEDBACK_TEST_PASSWORD", PASSWORD);
        Process browser = builder.start();
        try {
            assertThat(browser.waitFor(180, TimeUnit.SECONDS)).isTrue();
            System.out.println(Files.readString(log));
            assertThat(browser.exitValue()).as("Feedback browser journeys").isZero();
        } finally { if (browser.isAlive()) browser.destroyForcibly(); }
        assertThat(jdbcTemplate.queryForObject("SELECT rating FROM patient_feedback WHERE appointment_id=?", Integer.class, id)).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM charge", Long.class)).isZero();
    }
}

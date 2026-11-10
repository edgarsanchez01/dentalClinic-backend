package com.dentalclinic.appointment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.web.servlet.MvcResult;

import com.dentalclinic.appointment.application.ClaimedEmail;
import com.dentalclinic.appointment.application.CommunicationClaimService;
import com.dentalclinic.appointment.application.CommunicationDispatcher;
import com.dentalclinic.identity.TestTime;

/**
 * F8/T1 · Dispatcher de la outbox EMAIL sobre el DDL real V006.
 *
 * <p>El emisor es falso ({@link RecordingEmailSender}): no hay conexión SMTP y ningún test envía
 * correo real. El emisor se registra en la base compartida para que todos los tests de citas
 * compartan un único contexto (y un único esquema), evitando recrear el DDL sobre la misma base.
 */
class CommunicationDispatchIntegrationTest extends AppointmentIntegrationTestSupport {

    private static final AtomicInteger KEYS = new AtomicInteger();

    @Autowired
    private CommunicationDispatcher dispatcher;
    @Autowired
    private CommunicationClaimService claimService;
    @Autowired
    private RecordingEmailSender sender;
    @Autowired
    private ApplicationContext applicationContext;

    @BeforeEach
    void resetSender() {
        sender.reset();
    }

    @Test
    void envioExitosoPasaDePendingASent() throws Exception {
        createAppointment(at(MARTES, 9, 0));
        Long id = firstCommunicationId();
        assertThat(statusOf(id)).isEqualTo("PENDING");

        int sent = dispatcher.dispatchOnce();

        assertThat(sent).isEqualTo(1);
        assertThat(sender.sentCount()).isEqualTo(1);
        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(1);
        assertThat(row.get("sent_at")).isNotNull();
        assertThat(row.get("next_retry_at")).isNull();
    }

    @Test
    void falloSMTPRegistraErrorYProgramaReintento() throws Exception {
        createAppointment(at(MARTES, 9, 0));
        Long id = firstCommunicationId();
        sender.alwaysFail();

        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(sender.sentCount()).isZero();

        Map<String, Object> trasFallo = row(id);
        assertThat(trasFallo.get("status")).isEqualTo("PENDING");
        assertThat(((Number) trasFallo.get("attempts")).intValue()).isEqualTo(1);
        assertThat(trasFallo.get("last_error")).isEqualTo("EmailSendException");
        assertThat(trasFallo.get("next_retry_at")).isNotNull();

        TestTime.advance(Duration.ofHours(2));
        sender.succeed();

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        Map<String, Object> trasExito = row(id);
        assertThat(trasExito.get("status")).isEqualTo("SENT");
        assertThat(((Number) trasExito.get("attempts")).intValue()).isEqualTo(2);
        assertThat(trasExito.get("last_error")).isNull();
    }

    @Test
    void agotaIntentosYPasaAFailedSinVolverAEnviar() throws Exception {
        createAppointment(at(MARTES, 9, 0));
        Long id = firstCommunicationId();
        sender.alwaysFail();

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(dispatcher.dispatchOnce()).isZero();
            TestTime.advance(Duration.ofHours(2));
        }

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(4);
        assertThat(row.get("last_error")).isEqualTo("EmailSendException");

        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(((Number) row(id).get("attempts")).intValue()).isEqualTo(4);
        assertThat(sender.sentCount()).isZero();
    }

    @Test
    void respetaNextRetryAtNoElegibleTodavia() throws Exception {
        createAppointment(at(MARTES, 9, 0));
        Long id = firstCommunicationId();
        Instant futuro = TestTime.now().plus(Duration.ofHours(1));
        jdbcTemplate.update("UPDATE communication SET next_retry_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(futuro, ZoneOffset.UTC), id);

        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(sender.sentCount()).isZero();
        assertThat(((Number) row(id).get("attempts")).intValue()).isZero();

        TestTime.advance(Duration.ofHours(2));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("SENT");
    }

    @Test
    void mensajesYaEnviadosOAnuladosNoSeRemiten() throws Exception {
        createAppointment(at(MARTES, 9, 0));
        Long sentId = firstCommunicationId();
        jdbcTemplate.update("UPDATE communication SET status = 'SENT', sent_at = now() WHERE id = ?",
                sentId);

        createAppointment(at(MARTES, 11, 0));
        Long supersededId = lastCommunicationId();
        jdbcTemplate.update("UPDATE communication SET superseded_at = now() WHERE id = ?",
                supersededId);

        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(sender.sentCount()).isZero();
        assertThat(statusOf(sentId)).isEqualTo("SENT");
        assertThat(statusOf(supersededId)).isEqualTo("PENDING");
    }

    @Test
    void dosDispatchersConcurrentesNoDuplicanElEnvio() throws Exception {
        createAppointment(at(MARTES, 9, 0));
        Long id = firstCommunicationId();
        sender.setSendDelayMillis(250);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> task = () -> {
            start.await();
            return dispatcher.dispatchOnce();
        };
        try {
            Future<Integer> first = pool.submit(task);
            Future<Integer> second = pool.submit(task);
            start.countDown();
            int total = first.get(30, TimeUnit.SECONDS) + second.get(30, TimeUnit.SECONDS);

            assertThat(total).isEqualTo(1);
            assertThat(sender.sentCount()).isEqualTo(1);
            assertThat(((Number) row(id).get("attempts")).intValue()).isEqualTo(1);
            assertThat(statusOf(id)).isEqualTo("SENT");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unClaimSinCompletarNoPierdeElMensajeYSeRecuperaTrasElLease() throws Exception {
        createAppointment(at(MARTES, 9, 0));
        Long id = firstCommunicationId();

        List<ClaimedEmail> claimed = claimService.claim(TestTime.now(), 10, Duration.ofMinutes(5));
        assertThat(claimed).hasSize(1);

        Map<String, Object> claimedRow = row(id);
        assertThat(claimedRow.get("status")).isEqualTo("PENDING");
        assertThat(((Number) claimedRow.get("attempts")).intValue()).isEqualTo(1);
        assertThat(claimedRow.get("next_retry_at")).isNotNull();

        assertThat(dispatcher.dispatchOnce()).isZero();

        TestTime.advance(Duration.ofMinutes(10));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("SENT");
        assertThat(((Number) row(id).get("attempts")).intValue()).isEqualTo(2);
    }

    @Test
    void losTestsNoUsanUnEmisorSmtpReal() {
        assertThat(dispatcher.isDeliveryConfigured()).isTrue();
        assertThat(applicationContext.getBeanNamesForType(JavaMailSender.class)).isEmpty();
    }

    // ------------------------------------------------------------- helpers

    private Long createAppointment(Instant start) throws Exception {
        String key = "dispatch-" + KEYS.incrementAndGet() + "-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post("/api/appointments")
                        .header("Authorization", bearer(loginToken(owner)))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(patient.getId(), dentist.getId(), start,
                                start.plus(Duration.ofHours(1)))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    private Map<String, Object> row(Long id) {
        return jdbcTemplate.queryForMap("SELECT * FROM communication WHERE id = ?", id);
    }

    private String statusOf(Long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM communication WHERE id = ?",
                String.class, id);
    }

    private Long firstCommunicationId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM communication ORDER BY id LIMIT 1", Long.class);
    }

    private Long lastCommunicationId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM communication ORDER BY id DESC LIMIT 1", Long.class);
    }
}

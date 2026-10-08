package com.dentalclinic.appointment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.dentalclinic.identity.domain.User;

/**
 * F6.D · Idempotencia de creación (A-16/O-2) y anti-doble-booking concurrente (A-14) sobre el DDL
 * real V006, donde viven las constraints que garantizan ambos comportamientos.
 */
class AppointmentIdempotencyApiTest extends AppointmentIntegrationTestSupport {

    private static final AtomicInteger KEYS = new AtomicInteger();

    @Test
    void laMismaClaveConElMismoCuerpoRepiteLaRespuestaOriginal() throws Exception {
        String key = nextKey();
        Long primero = idOf(create(owner, key, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0)).andExpect(status().isCreated()));

        Long segundo = idOf(create(owner, key, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0)).andExpect(status().isCreated()));

        assertThat(segundo).isEqualTo(primero);
        assertThat(countRows("appointment")).isEqualTo(1);
        assertThat(countRows("http_idempotency")).isEqualTo(1);
        assertThat(countRows("appointment_event")).isEqualTo(1);
    }

    @Test
    void laMismaClaveConOtroCuerpoResponde409SinConsumirse() throws Exception {
        String key = nextKey();
        Long primero = idOf(create(owner, key, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0)).andExpect(status().isCreated()));

        create(owner, key, patient.getId(), dentist.getId(), at(MARTES, 11, 0), at(MARTES, 12, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "La cabecera Idempotency-Key ya fue usada por otra petición con distinto contenido."));

        assertThat(countRows("appointment")).isEqualTo(1);
        assertThat(countRows("http_idempotency")).isEqualTo(1);
        assertThat(appointmentRepository.findById(primero)).isPresent();
    }

    /** A-16: una creación fallida no consume la clave; el reintento con esa clave puede triunfar. */
    @Test
    void unaCreacionFallidaNoDejaLaClaveConsumida() throws Exception {
        String key = nextKey();
        create(owner, key, patient.getId(), dentist.getId(), at(MARTES, 20, 0),
                at(MARTES, 21, 0)).andExpect(status().isConflict());

        assertThat(countRows("http_idempotency")).isZero();
        assertThat(countRows("appointment")).isZero();

        create(owner, key, patient.getId(), dentist.getId(), at(MARTES, 9, 0),
                at(MARTES, 10, 0)).andExpect(status().isCreated());

        assertThat(countRows("http_idempotency")).isEqualTo(1);
        assertThat(countRows("appointment")).isEqualTo(1);
    }

    /** A-16: el replay devuelve la respuesta original aunque la cita haya cambiado desde entonces. */
    @Test
    void elReintentoTrasCancelarConservaLaRespuestaOriginal() throws Exception {
        String key = nextKey();
        Long id = idOf(create(owner, key, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0)).andExpect(status().isCreated()));

        mockMvc.perform(post("/api/appointments/{id}/cancel", id)
                        .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        Long repetido = idOf(create(owner, key, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCHEDULED")));

        assertThat(repetido).isEqualTo(id);
        assertThat(countRows("appointment")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM appointment WHERE id = ?", String.class, id))
                .isEqualTo("CANCELLED");
        assertThat(countRows("appointment_event")).isEqualTo(2);
    }

    @RepeatedTest(10)
    void dosPeticionesConcurrentesConLaMismaClaveCreanUnaSolaCita() throws Exception {
        String key = nextKey();
        String token = loginToken(owner);

        List<Integer> statuses = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        runConcurrently(2, () -> {
            var result = mockMvc.perform(post("/api/appointments")
                            .header("Authorization", bearer(token))
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(patient.getId(), dentist.getId(),
                                    at(MARTES, 9, 0), at(MARTES, 10, 0))))
                    .andReturn();
            synchronized (this) {
                statuses.add(result.getResponse().getStatus());
                if (result.getResponse().getStatus() == 201) {
                    ids.add(objectMapper.readTree(result.getResponse().getContentAsString())
                            .get("id").asLong());
                }
            }
        });

        assertThat(statuses).containsExactlyInAnyOrder(201, 201);
        assertThat(ids).hasSize(2);
        assertThat(ids.get(0)).isEqualTo(ids.get(1));
        assertThat(countRows("appointment")).isEqualTo(1);
        assertThat(countRows("http_idempotency")).isEqualTo(1);
        assertThat(countRows("appointment_event")).isEqualTo(1);
    }

    @RepeatedTest(10)
    void dosPeticionesConcurrentesPorElMismoSlotSoloUnaTieneExito() throws Exception {
        String token = loginToken(owner);
        List<String> messages = new ArrayList<>();
        List<Integer> statuses = new ArrayList<>();
        runConcurrently(2, () -> {
            var result = mockMvc.perform(post("/api/appointments")
                            .header("Authorization", bearer(token))
                            .header("Idempotency-Key", nextKey())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody(patient.getId(), dentist.getId(),
                                    at(MARTES, 9, 0), at(MARTES, 10, 0))))
                    .andReturn();
            synchronized (this) {
                statuses.add(result.getResponse().getStatus());
                messages.add(result.getResponse().getContentAsString());
            }
        });

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        String perdedor = messages.get(statuses.indexOf(409));
        assertThat(perdedor).contains("Ese horario ya fue tomado. Selecciona otro horario.");
        assertThat(countRows("appointment")).isEqualTo(1);
    }

    @Test
    void lasClavesSonIndependientesPorActor() throws Exception {
        String key = nextKey();
        Long delOwner = idOf(create(owner, key, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0)).andExpect(status().isCreated()));

        create(receptionist, key, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Ese horario ya fue tomado. Selecciona otro horario."));

        assertThat(delOwner).isNotNull();
        assertThat(countRows("appointment")).isEqualTo(1);
        assertThat(countRows("http_idempotency")).isEqualTo(1);
    }

    // ------------------------------------------------------------- helpers

    private ResultActions create(User actor, String key, Long patientId, Long dentistId,
            Instant start, Instant end) throws Exception {
        return mockMvc.perform(post("/api/appointments")
                .header("Authorization", bearer(loginToken(actor)))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(patientId, dentistId, start, end)));
    }

    private Long idOf(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString())
                .get("id").asLong();
    }

    private static String nextKey() {
        return "k-" + KEYS.incrementAndGet() + "-" + UUID.randomUUID();
    }

    @FunctionalInterface
    private interface HttpTask {
        void run() throws Exception;
    }

    private void runConcurrently(int threads, HttpTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                    task.run();
                } catch (Throwable t) {
                    synchronized (errors) {
                        errors.add(t);
                    }
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertThat(errors).isEmpty();
    }
}

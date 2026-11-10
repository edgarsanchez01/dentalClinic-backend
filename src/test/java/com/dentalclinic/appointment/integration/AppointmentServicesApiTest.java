package com.dentalclinic.appointment.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.catalog.application.ServiceCatalogService.ServiceData;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;

class AppointmentServicesApiTest extends AppointmentIntegrationTestSupport {
    @Autowired PlatformTransactionManager transactions;

    private Long service(int duration) {
        return catalog.create(new ServiceData("Consulta de valoración", "Valoración inicial",
                new BigDecimal("999999999999999.9999"), duration)).getId();
    }
    private String body(Long serviceId, Instant start, Instant end) throws Exception {
        return objectMapper.writeValueAsString(Map.of("patientId", patient.getId(), "dentistId", dentist.getId(),
                "serviceId", serviceId, "startAt", start.toString(), "endAt", end.toString()));
    }
    private org.springframework.test.web.servlet.ResultActions create(String body, String key, User actor) throws Exception {
        return mockMvc.perform(post("/api/appointments").header("Authorization", bearer(loginToken(actor)))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void cadaRolAutorizadoCreaConServicioActivoYDuracionAutoritativa(Role role) throws Exception {
        User actor = switch (role) { case OWNER -> owner; case DENTIST -> dentistUser;
            case RECEPTIONIST -> receptionist; case PATIENT -> patientUser; };
        var response = create(body(service(60), at(MARTES, 9, 0), at(MARTES, 10, 0)), UUID.randomUUID().toString(), actor)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.service.name").value("Consulta de valoración"))
                .andExpect(jsonPath("$.service.durationMinutes").value(60)).andExpect(jsonPath("$.durationMinutes").value(60)).andReturn();
        assertThat(response.getResponse().getContentAsString()).contains("\"referencePrice\":999999999999999.9999");
    }

    @Test
    void omitirServicioOIntervaloManipuladoNoConsumeIdempotenciaNiCreaCita() throws Exception {
        Long id = service(60);
        String valid = body(id, at(MARTES, 9, 0), at(MARTES, 10, 0));
        var missing = objectMapper.readTree(valid).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) missing).remove("serviceId");
        create(missing.toString(), "missing", owner).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.serviceId").exists());
        create(body(id, at(MARTES, 9, 0), at(MARTES, 9, 15)), "wrong-duration", owner).andExpect(status().isBadRequest());
        assertThat(countRows("appointment")).isZero();
        assertThat(countRows("http_idempotency")).isZero();
        // Even extra client price/duration fields cannot replace catalog values.
        var tampered = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(valid);
        tampered.put("referencePrice", 0).put("durationMinutes", 1);
        create(tampered.toString(), "valid", owner).andExpect(status().isCreated()).andExpect(jsonPath("$.durationMinutes").value(60));
    }

    @Test
    void servicioInexistenteOInactivoEsRechazadoSinReservar() throws Exception {
        create(body(Long.MAX_VALUE, at(MARTES, 9, 0), at(MARTES, 10, 0)), "missing-service", owner).andExpect(status().isNotFound());
        Long id = service(60); catalog.setActive(id, false);
        create(body(id, at(MARTES, 9, 0), at(MARTES, 10, 0)), "inactive-service", owner).andExpect(status().isConflict());
        assertThat(countRows("appointment")).isZero();
    }

    @Test
    void identificadorFraccionarioNoSeleccionaOtroServicioNiDevuelve500() throws Exception {
        String request = body(service(60), at(MARTES, 9, 0), at(MARTES, 10, 0));
        var data = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(request);
        data.put("serviceId", 1.5);
        create(data.toString(), "fractional-service", owner).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/availability").header("Authorization", bearer(loginToken(owner)))
                .param("dentistId", dentist.getId().toString()).param("from", MARTES.toString()).param("serviceId", "1.5"))
                .andExpect(status().isBadRequest());
        assertThat(countRows("appointment")).isZero();
    }

    @Test
    void disponibilidadPorServicioIgnoraDuracionClienteYRespetaVigencia() throws Exception {
        Long id = service(60);
        mockMvc.perform(get("/api/availability").header("Authorization", bearer(loginToken(patientUser)))
                .param("dentistId", dentist.getId().toString()).param("from", MARTES.toString())
                .param("serviceId", id.toString()).param("duration", "15"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.durationMinutes").value(60))
                .andExpect(jsonPath("$.slots[0].startTime").value("09:00:00"))
                .andExpect(jsonPath("$.slots[0].endTime").value("10:00:00"));
        catalog.setActive(id, false);
        mockMvc.perform(get("/api/availability").header("Authorization", bearer(loginToken(owner)))
                .param("dentistId", dentist.getId().toString()).param("from", MARTES.toString()).param("serviceId", id.toString()))
                .andExpect(status().isConflict());
    }

    @Test
    void snapshotYReplayConservanNombrePrecioYDuracionTrasEditarYDesactivarCatalogo() throws Exception {
        Long id = service(60);
        String request = body(id, at(MARTES, 9, 0), at(MARTES, 10, 0));
        var original = create(request, "history", owner).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Long appointmentId = objectMapper.readTree(original).get("id").asLong();
        catalog.update(id, new ServiceData("Nombre cambiado", "Otra descripción", BigDecimal.ZERO, 90));
        catalog.setActive(id, false);
        String replay = create(request, "history", owner).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(original);
        mockMvc.perform(get("/api/appointments/{id}", appointmentId).header("Authorization", bearer(loginToken(patientUser))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.service.name").value("Consulta de valoración"))
                .andExpect(jsonPath("$.service.durationMinutes").value(60));
        assertThat(jdbcTemplate.queryForObject("select service_reference_price from appointment where id=?", BigDecimal.class, appointmentId))
                .isEqualByComparingTo("999999999999999.9999");
        assertThat(countRows("appointment")).isEqualTo(1);
    }

    @Test
    void cambiarServicioConMismaClaveEsConflicto() throws Exception {
        Long first = service(60), second = service(60);
        create(body(first, at(MARTES, 9, 0), at(MARTES, 10, 0)), "service-hash", owner).andExpect(status().isCreated());
        create(body(second, at(MARTES, 9, 0), at(MARTES, 10, 0)), "service-hash", owner).andExpect(status().isConflict());
        assertThat(countRows("appointment")).isEqualTo(1);
    }

    @Test
    void reprogramarConservaSnapshotYDuracionInclusoConServicioInactivo() throws Exception {
        Long id = service(60);
        var original = create(body(id, at(MARTES, 9, 0), at(MARTES, 10, 0)), "reschedule-service", owner).andExpect(status().isCreated()).andReturn();
        long aid = objectMapper.readTree(original.getResponse().getContentAsString()).get("id").asLong();
        catalog.update(id, new ServiceData("Cambiado", null, BigDecimal.ZERO, 90)); catalog.setActive(id, false);
        mockMvc.perform(get("/api/availability").header("Authorization", bearer(loginToken(owner)))
                .param("dentistId", dentist.getId().toString()).param("from", MIERCOLES.toString())
                .param("appointmentId", String.valueOf(aid)).param("duration", "90"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.durationMinutes").value(60));
        String reschedule = "{\"startAt\":\"%s\",\"endAt\":\"%s\",\"expectedOccurrenceVersion\":1}";
        mockMvc.perform(post("/api/appointments/{id}/reschedule", aid).header("Authorization", bearer(loginToken(owner)))
                .contentType(MediaType.APPLICATION_JSON).content(reschedule.formatted(at(MIERCOLES, 9, 0), at(MIERCOLES, 10, 30))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/appointments/{id}/reschedule", aid).header("Authorization", bearer(loginToken(owner)))
                .contentType(MediaType.APPLICATION_JSON).content(reschedule.formatted(at(MIERCOLES, 9, 0), at(MIERCOLES, 10, 0))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.service.name").value("Consulta de valoración"))
                .andExpect(jsonPath("$.durationMinutes").value(60));
    }

    @Test
    void historicaSinServicioSigueLegibleReprogramableYCancelable() throws Exception {
        Appointment old = appointmentRepository.saveAndFlush(Appointment.create(patient.getId(), dentist.getId(), owner.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0), 60, "Histórica", null));
        mockMvc.perform(get("/api/appointments/{id}", old.getId()).header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.service").doesNotExist());
        User otherPatient = newUser(Role.PATIENT);
        mockMvc.perform(get("/api/availability").header("Authorization", bearer(loginToken(otherPatient)))
                .param("dentistId", dentist.getId().toString()).param("from", MIERCOLES.toString()).param("appointmentId", old.getId().toString()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/appointments/{id}/reschedule", old.getId()).header("Authorization", bearer(loginToken(owner)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"startAt\":\"%s\",\"endAt\":\"%s\",\"expectedOccurrenceVersion\":1}".formatted(
                        at(MIERCOLES, 9, 0), at(MIERCOLES, 10, 0))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.service").doesNotExist());
        mockMvc.perform(post("/api/appointments/{id}/cancel", old.getId()).header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void lockDeReservaSerializaEdicionDeCatalogo() throws Exception {
        Long id = service(60);
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var reader = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                catalog.resolveForBooking(id); locked.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
                return null;
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var writer = executor.submit(() -> catalog.setActive(id, false));
            try { assertThatThrownBy(() -> writer.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            reader.get(5, TimeUnit.SECONDS); writer.get(5, TimeUnit.SECONDS);
            assertThat(catalog.get(id).isActive()).isFalse();
        }
    }
}

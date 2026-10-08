package com.dentalclinic.appointment.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.patient.domain.Patient;

/**
 * F6.D · Listado, filtros y visibilidad de la agenda (§17.5, RF-AP-10, matriz §7.4 SELF_READ).
 */
class AppointmentListApiTest extends AppointmentIntegrationTestSupport {

    private static final AtomicInteger KEYS = new AtomicInteger();

    @Test
    void unPacienteNuncaVeCitasAjenasAunquePidaElFiltro() throws Exception {
        Long propia = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));
        User otroUsuario = newUser(Role.PATIENT);
        Patient ajeno = patientRepository.saveAndFlush(Patient.createForUser(otroUsuario.getId(),
                "Otro", "Paciente", otroUsuario.getEmail(), null, null, null));
        Long ajena = createAndId(owner, ajeno.getId(), dentist.getId(),
                at(MARTES, 11, 0), at(MARTES, 12, 0));

        mockMvc.perform(get("/api/appointments?patientId=" + ajeno.getId())
                        .header("Authorization", bearer(loginToken(patientUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(propia));

        mockMvc.perform(get("/api/appointments/" + ajena)
                        .header("Authorization", bearer(loginToken(patientUser))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/appointments/" + ajena + "/events")
                        .header("Authorization", bearer(loginToken(patientUser))))
                .andExpect(status().isNotFound());
    }

    @Test
    void filtraPorEstadoProfesionalYRango() throws Exception {
        Long manana = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 9, 0), at(MARTES, 10, 0));
        Long tarde = createAndId(owner, patient.getId(), dentist.getId(),
                at(MARTES, 15, 0), at(MARTES, 16, 0));
        createAndId(owner, patient.getId(), dentist.getId(),
                at(MIERCOLES, 9, 0), at(MIERCOLES, 10, 0));

        mockMvc.perform(post("/api/appointments/{id}/cancel", manana)
                        .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/appointments?status=CANCELLED")
                        .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(manana));

        mockMvc.perform(get("/api/appointments?status=SCHEDULED&dentistId=" + dentist.getId())
                        .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(tarde));

        mockMvc.perform(get("/api/appointments?from=2026-10-14T00:00:00Z"
                        + "&to=2026-10-15T00:00:00Z")
                        .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].startAt").value(at(MIERCOLES, 9, 0).toString()));
    }

    @Test
    void laPaginacionEstaAcotadaYElOrdenEsPorInicio() throws Exception {
        createAndId(owner, patient.getId(), dentist.getId(), at(MARTES, 15, 0),
                at(MARTES, 16, 0));
        createAndId(owner, patient.getId(), dentist.getId(), at(MARTES, 9, 0),
                at(MARTES, 10, 0));
        createAndId(owner, patient.getId(), dentist.getId(), at(MIERCOLES, 9, 0),
                at(MIERCOLES, 10, 0));

        mockMvc.perform(get("/api/appointments?size=1000&page=-3")
                        .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].startAt").value(at(MARTES, 9, 0).toString()))
                .andExpect(jsonPath("$.content[1].startAt").value(at(MARTES, 15, 0).toString()))
                .andExpect(jsonPath("$.content[2].startAt").value(at(MIERCOLES, 9, 0).toString()));

        mockMvc.perform(get("/api/appointments?page=9&size=2")
                        .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void unPacienteSinPerfilListaLaAgendaVacia() throws Exception {
        User sinPerfil = newUser(Role.PATIENT);
        mockMvc.perform(get("/api/appointments")
                        .header("Authorization", bearer(loginToken(sinPerfil))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    // ------------------------------------------------------------- helpers

    private Long createAndId(User actor, Long patientId, Long dentistId, Instant start,
            Instant end) throws Exception {
        var result = mockMvc.perform(post("/api/appointments")
                        .header("Authorization", bearer(loginToken(actor)))
                        .header("Idempotency-Key", "k-" + KEYS.incrementAndGet() + "-"
                                + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(patientId, dentistId, start, end)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }
}

package com.dentalclinic.appointment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.dentalclinic.clinic.domain.Dentist;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.patient.domain.Patient;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * UX-05B.1 · Alcance de lectura de citas del rol {@code DENTIST}.
 *
 * <p>Regla aprobada: el flujo de atención y cobro del profesional opera <strong>exclusivamente</strong>
 * sobre las citas asignadas al dentista autenticado. El backend es la autoridad: el
 * {@code dentistId} que llegue del cliente se ignora y el alcance se resuelve desde la identidad
 * autenticada y el perfil profesional persistido (fail-closed sin perfil).
 *
 * <p>Lo que este test fija:
 * <ul>
 *   <li>el {@code DENTIST} solo lista, abre y revisa los eventos de sus propias citas;</li>
 *   <li>una cita ajena se responde con <strong>404</strong> (no se revela su existencia);</li>
 *   <li>una identidad {@code DENTIST} sin perfil profesional no demuestra pertenencia: listado
 *       vacío y <strong>403</strong> en detalle/eventos <em>sin consultar antes si la cita
 *       existe</em>: un identificador existente y uno inexistente responden lo mismo, de modo que
 *       no se puede enumerar la agenda de la clínica;</li>
 *   <li>{@code OWNER} y {@code RECEPTIONIST} conservan el alcance global; {@code PATIENT} conserva
 *       el suyo;</li>
 *   <li>los filtros del cliente (estado, rango, paciente) <strong>se componen</strong> con el
 *       alcance propio y no lo sustituyen;</li>
 *   <li>el acceso clínico <strong>longitudinal</strong> al expediente no se restringe: el
 *       profesional sigue consultando la trazabilidad y puede vincular un tratamiento a una cita
 *       del mismo paciente atendida por otro profesional.</li>
 * </ul>
 */
class AppointmentDentistScopeApiTest extends AppointmentIntegrationTestSupport {

    private static final AtomicInteger KEYS = new AtomicInteger();

    /**
     * Identificador que no existe en la base. La secuencia real de {@code appointment} nunca lo
     * alcanza en una suite, de modo que la respuesta 404 significa «no existe» y no «existe y no
     * es tuya».
     */
    private static final long ID_INEXISTENTE = 9_999_999_999L;

    /** Segundo profesional: sus citas son el material de fuga que este test debe impedir. */
    private User otroUsuario;
    private Dentist otroDentista;

    @BeforeEach
    void seedOtroProfesional() {
        otroUsuario = newUser(Role.DENTIST);
        otroDentista = seedDentistFor(otroUsuario);
    }

    // ------------------------------------------------------------- alcance propio

    @Test
    void elDentistaSoloListaSusPropiasCitas() throws Exception {
        Long propia = crearComo(owner, patient.getId(), dentist.getId(), MARTES, 9);
        crearComo(owner, patient.getId(), otroDentista.getId(), MARTES, 11);

        mockMvc.perform(get("/api/appointments").header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(propia))
                .andExpect(jsonPath("$.content[0].dentistId").value(dentist.getId()));
    }

    @Test
    void elIdentificadorDeDentistaDelClienteSeIgnora() throws Exception {
        Long propia = crearComo(owner, patient.getId(), dentist.getId(), MARTES, 9);
        crearComo(owner, patient.getId(), otroDentista.getId(), MARTES, 11);

        mockMvc.perform(get("/api/appointments").param("dentistId", String.valueOf(otroDentista.getId()))
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(propia));

        // Un identificador inexistente tampoco amplía el alcance.
        mockMvc.perform(get("/api/appointments").param("dentistId", "999999")
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(propia));
    }

    @Test
    void losFiltrosYLaPaginacionNoFiltranCitasAjenas() throws Exception {
        List<Long> propias = List.of(
                crearComo(owner, patient.getId(), dentist.getId(), MARTES, 9),
                crearComo(owner, patient.getId(), dentist.getId(), MARTES, 10),
                crearComo(owner, patient.getId(), dentist.getId(), MARTES, 11));
        List<Long> ajenas = List.of(
                crearComo(owner, patient.getId(), otroDentista.getId(), MARTES, 13),
                crearComo(owner, patient.getId(), otroDentista.getId(), MARTES, 14));

        for (int page = 0; page < 3; page++) {
            mockMvc.perform(get("/api/appointments").param("page", String.valueOf(page)).param("size", "1")
                            .param("from", "2026-10-13T00:00:00Z").param("to", "2026-10-14T00:00:00Z")
                            .header("Authorization", bearer(loginToken(dentistUser))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.size").value(1))
                    .andExpect(jsonPath("$.content.length()").value(1));
        }

        // El estado y el rango se combinan con el alcance propio, no lo sustituyen.
        mockMvc.perform(get("/api/appointments").param("status", "SCHEDULED")
                        .param("from", "2026-10-13T00:00:00Z").param("to", "2026-10-14T00:00:00Z")
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));

        List<Long> visibles = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            var result = mockMvc.perform(get("/api/appointments").param("page", String.valueOf(page))
                            .param("size", "1").header("Authorization", bearer(loginToken(dentistUser))))
                    .andExpect(status().isOk()).andReturn();
            var content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
            content.forEach(node -> visibles.add(node.get("id").asLong()));
        }
        assertThat(visibles).containsExactlyInAnyOrderElementsOf(propias);
        assertThat(visibles).doesNotContainAnyElementsOf(ajenas);
    }

    @Test
    void elFiltroPorPacienteSeCombinaConElAlcancePropio() throws Exception {
        Long propia = crearComo(owner, patient.getId(), dentist.getId(), MARTES, 9);
        crearComo(owner, patient.getId(), otroDentista.getId(), MARTES, 11);

        mockMvc.perform(get("/api/appointments").param("patientId", String.valueOf(patient.getId()))
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(propia));
    }

    /**
     * Los filtros del cliente <strong>se componen</strong> con el alcance propio; ninguno de los
     * dos puede sustituirse por el otro. Para que la prueba discrimine de verdad, el conjunto de
     * citas tiene un estado distinto, una fecha fuera de rango, un segundo paciente y una cita
     * ajena que <em>cumple</em> todos los filtros del cliente (mismo paciente, mismo rango, mismo
     * estado) y que solo el alcance puede excluir.
     */
    @Test
    void losFiltrosSeComponenConElAlcancePropioYNoLoSustituyen() throws Exception {
        Patient otro = otroPaciente();

        Long propiaProgramada = crearComo(owner, patient.getId(), dentist.getId(), MARTES, 9);
        Long propiaProgramadaTarde = crearComo(owner, patient.getId(), dentist.getId(), MARTES, 10);
        Long propiaCancelada = crearComo(owner, patient.getId(), dentist.getId(), MARTES, 11);
        Long propiaDeOtroPaciente = crearComo(owner, otro.getId(), dentist.getId(), MARTES, 12);
        Long propiaFueraDeRango = crearComo(owner, patient.getId(), dentist.getId(), MIERCOLES, 9);
        // Ajena: mismo paciente, mismo rango, mismo estado y otro profesional.
        Long ajena = crearComo(owner, patient.getId(), otroDentista.getId(), MARTES, 13);

        mockMvc.perform(post("/api/appointments/{id}/cancel", propiaCancelada)
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isOk());

        // Sin filtros: solo las cinco propias; la ajena queda fuera por alcance, no por filtro.
        JsonNode pagina = listarJson(listar(dentistUser));
        assertThat(pagina.get("totalElements").asInt()).isEqualTo(5);
        assertThat(idsDe(pagina)).containsExactlyInAnyOrder(propiaProgramada, propiaProgramadaTarde,
                propiaCancelada, propiaDeOtroPaciente, propiaFueraDeRango)
                .doesNotContain(ajena);

        // Rango [2026-10-13, 2026-10-14): el miércoles queda fuera.
        pagina = listarJson(listarRango(dentistUser));
        assertThat(pagina.get("totalElements").asInt()).isEqualTo(4);
        assertThat(idsDe(pagina)).containsExactlyInAnyOrder(propiaProgramada, propiaProgramadaTarde,
                propiaCancelada, propiaDeOtroPaciente);

        // Rango + estado: solo las tres programadas que caen en el rango.
        pagina = listarJson(listarRango(dentistUser).param("status", "SCHEDULED"));
        assertThat(pagina.get("totalElements").asInt()).isEqualTo(3);
        assertThat(idsDe(pagina)).containsExactlyInAnyOrder(propiaProgramada, propiaProgramadaTarde,
                propiaDeOtroPaciente);

        // Rango + paciente: la cita del otro paciente queda fuera.
        pagina = listarJson(listarRango(dentistUser).param("patientId", String.valueOf(patient.getId())));
        assertThat(pagina.get("totalElements").asInt()).isEqualTo(3);
        assertThat(idsDe(pagina)).containsExactlyInAnyOrder(propiaProgramada, propiaProgramadaTarde,
                propiaCancelada);

        // Rango + estado + paciente: la cita ajena cumple los tres filtros del cliente y aun así no
        // aparece: la excluye el alcance propio, no un filtro.
        pagina = listarJson(listarRango(dentistUser).param("status", "SCHEDULED")
                .param("patientId", String.valueOf(patient.getId())));
        assertThat(pagina.get("totalElements").asInt()).isEqualTo(2);
        assertThat(idsDe(pagina)).containsExactly(propiaProgramada, propiaProgramadaTarde);

        // Un solo filtro de estado: solo la cancelada.
        pagina = listarJson(listar(dentistUser).param("status", "CANCELLED"));
        assertThat(pagina.get("totalElements").asInt()).isEqualTo(1);
        assertThat(idsDe(pagina)).containsExactly(propiaCancelada);

        // Un solo filtro de paciente: las cuatro propias de ese paciente, incluido el miércoles.
        pagina = listarJson(listar(dentistUser).param("patientId", String.valueOf(patient.getId())));
        assertThat(pagina.get("totalElements").asInt()).isEqualTo(4);
        assertThat(idsDe(pagina)).containsExactlyInAnyOrder(propiaProgramada, propiaProgramadaTarde,
                propiaCancelada, propiaFueraDeRango);
    }

    // ------------------------------------------------------------- detalle y eventos

    @Test
    void elDentistaAbreSuCitaYSusEventos() throws Exception {
        Long propia = crearComo(owner, patient.getId(), dentist.getId(), MARTES, 9);

        mockMvc.perform(get("/api/appointments/{id}", propia)
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(propia))
                .andExpect(jsonPath("$.dentistId").value(dentist.getId()));

        mockMvc.perform(get("/api/appointments/{id}/events", propia)
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("APPOINTMENT_CREATED"));
    }

    @Test
    void laCitaAjenaNoExisteParaElDentistaNiEnDetalleNiEnEventos() throws Exception {
        Long ajena = crearComo(owner, patient.getId(), otroDentista.getId(), MARTES, 9);

        mockMvc.perform(get("/api/appointments/{id}", ajena)
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/appointments/{id}/events", ajena)
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isNotFound());

        // Con perfil profesional, la respuesta tampoco depende de si la cita existe: 404 en ambos
        // casos, de modo que un identificador inexistente no delata nada que el ajeno no delate.
        mockMvc.perform(get("/api/appointments/{id}", ID_INEXISTENTE)
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/appointments/{id}/events", ID_INEXISTENTE)
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------- identidad sin perfil

    @Test
    void unaIdentidadDentistaSinPerfilNoDemuestraPertenencia() throws Exception {
        Long ajena = crearComo(owner, patient.getId(), dentist.getId(), MARTES, 9);
        User sinPerfil = newUser(Role.DENTIST);

        mockMvc.perform(get("/api/appointments").header("Authorization", bearer(loginToken(sinPerfil))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());

        mockMvc.perform(get("/api/appointments/{id}", ajena)
                        .header("Authorization", bearer(loginToken(sinPerfil))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Tu cuenta no tiene un perfil de dentista."));

        mockMvc.perform(get("/api/appointments/{id}/events", ajena)
                        .header("Authorization", bearer(loginToken(sinPerfil))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Tu cuenta no tiene un perfil de dentista."));
    }

    /**
     * Sin perfil profesional no se consulta la cita: la respuesta es 403 tanto si el
     * identificador existe como si no. De lo contrario, el par (403, 404) revelaría qué
     * identificadores de cita existen en la clínica.
     */
    @Test
    void unaIdentidadDentistaSinPerfilNoDistingueCitasExistentesDeInexistentes() throws Exception {
        Long existente = crearComo(owner, patient.getId(), dentist.getId(), MARTES, 9);
        User sinPerfil = newUser(Role.DENTIST);

        for (Long id : List.of(existente, ID_INEXISTENTE)) {
            mockMvc.perform(get("/api/appointments/{id}", id)
                            .header("Authorization", bearer(loginToken(sinPerfil))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("Tu cuenta no tiene un perfil de dentista."));

            mockMvc.perform(get("/api/appointments/{id}/events", id)
                            .header("Authorization", bearer(loginToken(sinPerfil))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("Tu cuenta no tiene un perfil de dentista."));
        }
    }

    // ------------------------------------------------------------- otros roles

    @Test
    void ownerYRecepcionistaConservanElAlcanceGlobal() throws Exception {
        Long enOtro = crearComo(owner, patient.getId(), otroDentista.getId(), MARTES, 9);

        for (User actor : List.of(owner, receptionist)) {
            mockMvc.perform(get("/api/appointments").header("Authorization", bearer(loginToken(actor))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].id").value(enOtro));

            mockMvc.perform(get("/api/appointments/{id}", enOtro)
                            .header("Authorization", bearer(loginToken(actor))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(enOtro));

            mockMvc.perform(get("/api/appointments/{id}/events", enOtro)
                            .header("Authorization", bearer(loginToken(actor))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].eventType").value("APPOINTMENT_CREATED"));
        }
    }

    @Test
    void elPacienteMantieneSuPropiaCitaYNoLaAjena() throws Exception {
        Long propia = crearComo(owner, patient.getId(), dentist.getId(), MARTES, 9);
        // La cita ajena es la de otro paciente: el del paciente sí ve la cita de otro profesional.
        User otroPacienteUsuario = newUser(Role.PATIENT);
        Patient otroPaciente = patientRepository.saveAndFlush(
                Patient.createForUser(otroPacienteUsuario.getId(), "Otro", "Paciente",
                        otroPacienteUsuario.getEmail(), null, null, null));
        Long ajena = crearComo(owner, otroPaciente.getId(), dentist.getId(), MARTES, 11);

        mockMvc.perform(get("/api/appointments/{id}", propia)
                        .header("Authorization", bearer(loginToken(patientUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(propia));

        mockMvc.perform(get("/api/appointments/{id}", ajena)
                        .header("Authorization", bearer(loginToken(patientUser))))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/appointments/{id}/events", ajena)
                        .header("Authorization", bearer(loginToken(patientUser))))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------- acceso clínico longitudinal

    @Test
    void elAccesoClinicoLongitudinalNoSeRestringe() throws Exception {
        // Cita del mismo paciente atendida por otro profesional.
        Long ajena = crearComo(owner, patient.getId(), otroDentista.getId(), MARTES, 9);

        mockMvc.perform(get("/api/patients/{patientId}/clinical/appointments/{appointmentId}/trace",
                        patient.getId(), ajena)
                        .param("occurrenceVersion", "1").param("section", "NOTES")
                        .header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context.appointmentId").value(ajena));

        // Y puede vincular un tratamiento a esa cita del mismo paciente.
        mockMvc.perform(post("/api/patients/{patientId}/clinical/treatments", patient.getId())
                        .header("Authorization", bearer(loginToken(dentistUser)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Plan sobre cita previa","appointmentId":%d}
                                """.formatted(ajena)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appointmentId").value(ajena));
    }

    // ------------------------------------------------------------- helpers

    /** Listado sin filtros: solo aplica el alcance propio. */
    private MockHttpServletRequestBuilder listar(User actor) throws Exception {
        return get("/api/appointments").header("Authorization", bearer(loginToken(actor)));
    }

    /** Listado acotado al martes: solo 2026-10-13. */
    private MockHttpServletRequestBuilder listarRango(User actor) throws Exception {
        return listar(actor).param("from", "2026-10-13T00:00:00Z")
                .param("to", "2026-10-14T00:00:00Z");
    }

    /** Respuesta del listado ya parseada. */
    private JsonNode listarJson(MockHttpServletRequestBuilder request) throws Exception {
        var result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** Identificadores devueltos por la página de listado. */
    private List<Long> idsDe(JsonNode pagina) {
        List<Long> ids = new ArrayList<>();
        pagina.get("content").forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }

    /** Segundo paciente, para comprobar que el filtro por paciente también discrimina. */
    private Patient otroPaciente() {
        User usuario = newUser(Role.PATIENT);
        return patientRepository.saveAndFlush(
                Patient.createForUser(usuario.getId(), "Otro", "Paciente", usuario.getEmail(),
                        null, null, null));
    }

    private Long crearComo(User actor, Long patientId, Long dentistId, LocalDate day, int hour)
            throws Exception {
        Instant start = at(day, hour, 0);
        Instant end = at(day, hour + 1, 0);
        var result = mockMvc.perform(post("/api/appointments")
                        .header("Authorization", bearer(loginToken(actor)))
                        .header("Idempotency-Key", "scope-" + KEYS.incrementAndGet() + "-"
                                + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(patientId, dentistId, start, end)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }
}

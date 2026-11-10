package com.dentalclinic.clinical.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.dentalclinic.appointment.integration.AppointmentIntegrationTestSupport;
import com.dentalclinic.clinic.domain.Dentist;
import com.dentalclinic.clinical.application.TreatmentService;
import com.dentalclinic.clinical.application.TreatmentService.TreatmentData;
import com.dentalclinic.clinical.domain.InvalidTreatmentException;
import com.dentalclinic.clinical.domain.TreatmentStatus;
import com.dentalclinic.clinical.infrastructure.repository.TreatmentRepository;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.patient.domain.Patient;

/**
 * UX-05B.1 · Frontera de la <strong>referencia clínica</strong> al registrar un tratamiento
 * (RF-TR-05, P-05).
 *
 * <p>La cita referenciada se resuelve como referencia entre módulos
 * ({@code AppointmentService.resolveReference}), <em>sin</em> alcance de agenda: por eso el
 * consumidor tiene que comprobar por su cuenta las dos condiciones que sí importan, el rol de
 * personal clínico y la pertenencia de la cita al paciente del tratamiento.
 *
 * <p>Lo que este test fija:
 * <ul>
 *   <li>un tratamiento del paciente A <strong>no</strong> puede referenciar una cita del paciente
 *       B, ni por HTTP ni invocando el servicio directamente;</li>
 *   <li>una invocación directa del servicio con un actor que no es personal clínico
 *       ({@code PATIENT}, {@code RECEPTIONIST}) se rechaza, aunque la cadena de URL ya lo
 *       restringiera;</li>
 *   <li>ningún rechazo deja un tratamiento persistido;</li>
 *   <li>se conserva el caso válido del expediente longitudinal: paciente correcto y cita atendida
 *       por otro profesional.</li>
 * </ul>
 */
class TreatmentClinicalReferenceApiTest extends AppointmentIntegrationTestSupport {

    private static final AtomicInteger KEYS = new AtomicInteger();

    @Autowired
    private TreatmentService treatmentService;

    @Autowired
    private TreatmentRepository treatmentRepository;

    /** Segundo profesional: su cita es la referencia longitudinal válida. */
    private User otroUsuario;
    private Dentist otroDentista;

    /** Segundo paciente: sus citas no pueden ser referenciadas por el primero. */
    private User otroPacienteUsuario;
    private Patient otroPaciente;

    @BeforeEach
    void seedSegundoProfesionalYSegundoPaciente() {
        otroUsuario = newUser(Role.DENTIST);
        otroDentista = seedDentistFor(otroUsuario);
        otroPacienteUsuario = newUser(Role.PATIENT);
        otroPaciente = patientRepository.saveAndFlush(
                Patient.createForUser(otroPacienteUsuario.getId(), "Otro", "Paciente",
                        otroPacienteUsuario.getEmail(), null, null, null));
    }

    // ------------------------------------------------------------- frontera negativa

    @Test
    void unaCitaDeOtroPacienteNoSePuedeReferenciar() throws Exception {
        Long citaDeOtro = crearComo(owner, otroPaciente.getId(), dentist.getId(), MARTES, 9);

        // Por HTTP: el dominio rechaza la referencia y explica por qué.
        mockMvc.perform(post("/api/patients/{id}/clinical/treatments", patient.getId())
                        .header("Authorization", bearer(loginToken(dentistUser)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Tratamiento con cita ajena","appointmentId":%d}
                                """.formatted(citaDeOtro)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La cita indicada no pertenece al paciente."));

        // Y también invocando el servicio directamente, sin pasar por el controlador.
        assertThatThrownBy(() -> treatmentService.create(patient.getId(),
                new TreatmentData("Tratamiento con cita ajena", null, TreatmentStatus.PLANNED,
                        citaDeOtro, null, null),
                identidadDe(owner)))
                .isInstanceOf(InvalidTreatmentException.class)
                .hasMessage("La cita indicada no pertenece al paciente.");

        // Ningún rechazo deja un tratamiento persistido.
        assertThat(treatmentRepository.count()).isZero();
    }

    @Test
    void elServicioRechazaActoresQueNoSonPersonalClinico() {
        for (User actor : List.of(patientUser, receptionist)) {
            assertThatThrownBy(() -> treatmentService.create(patient.getId(),
                    new TreatmentData("Intento no autorizado", null, TreatmentStatus.PLANNED,
                            null, null, null),
                    identidadDe(actor)))
                    .isInstanceOf(ForbiddenOperationException.class)
                    .hasMessage("Solo el personal clínico puede registrar tratamientos.");
        }

        // Tampoco el rechazo por rol deja un tratamiento persistido.
        assertThat(treatmentRepository.count()).isZero();
    }

    // ------------------------------------------------------------- caso válido conservado

    @Test
    void elExpedienteLongitudinalSeConservaParaElMismoPaciente() throws Exception {
        // Cita del mismo paciente atendida por otro profesional.
        Long citaDeOtroProfesional = crearComo(owner, patient.getId(), otroDentista.getId(),
                MARTES, 9);

        mockMvc.perform(post("/api/patients/{id}/clinical/treatments", patient.getId())
                        .header("Authorization", bearer(loginToken(dentistUser)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Plan sobre cita de otro profesional","appointmentId":%d}
                                """.formatted(citaDeOtroProfesional)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appointmentId").value(citaDeOtroProfesional));

        // Y también por invocación directa del servicio.
        var tratamiento = treatmentService.create(patient.getId(),
                new TreatmentData("Plan directo", null, TreatmentStatus.PLANNED,
                        citaDeOtroProfesional, null, null),
                identidadDe(dentistUser));

        assertThat(tratamiento.getAppointmentId()).isEqualTo(citaDeOtroProfesional);
        assertThat(tratamiento.getPatientId()).isEqualTo(patient.getId());
        assertThat(treatmentRepository.count()).isEqualTo(2);
    }

    // ------------------------------------------------------------- helpers

    /** Identidad autenticada tal y como la resolvería la capa de seguridad. */
    private static AuthenticatedUser identidadDe(User user) {
        return new AuthenticatedUser(user.getId(), user.getRole());
    }

    private Long crearComo(User actor, Long patientId, Long dentistId, LocalDate day, int hour)
            throws Exception {
        var result = mockMvc.perform(post("/api/appointments")
                        .header("Authorization", bearer(loginToken(actor)))
                        .header("Idempotency-Key", "clin-ref-" + KEYS.incrementAndGet() + "-"
                                + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(patientId, dentistId, at(day, hour, 0), at(day, hour + 1, 0))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }
}

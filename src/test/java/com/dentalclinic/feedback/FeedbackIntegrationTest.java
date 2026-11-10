package com.dentalclinic.feedback;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.MediaType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.dentalclinic.appointment.integration.*;
import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.appointment.application.*;
import com.dentalclinic.feedback.infrastructure.FeedbackRepository;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.domain.Role;

/** V001–V017 + validate sobre PostgreSQL16 Testcontainers, SMTP sustituido por emisor falso. */
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:tc:postgresql:16:///dentalclinic_feedback_5c",
    "dentalclinic.feedback.public-base-url=http://localhost:5196"
})
class FeedbackIntegrationTest extends AppointmentIntegrationTestSupport {
    @Autowired RecordingEmailSender sender;
    @Autowired CommunicationDispatcher dispatcher;
    @Autowired FeedbackRepository feedbackRepository;
    @Autowired PlatformTransactionManager transactions;
    @Autowired AppointmentService appointments;

    private Long appointment(int hour) {
        return appointmentRepository.saveAndFlush(Appointment.create(patient.getId(), dentist.getId(), owner.getId(),
                at(MARTES, hour, 0), at(MARTES, hour + 1, 0), 60, "Diagnóstico que no debe salir", "Expediente privado")).getId();
    }

    private Long complete() throws Exception {
        Long id = appointment(9);
        mockMvc.perform(completeRequest(id).header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        return id;
    }

    private String emailToken() {
        sender.reset();
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        var email = sender.messages().getFirst();
        assertThat(email.subject()).isEqualTo("¿Cómo fue tu atención?");
        assertThat(email.body()).contains("7 días").doesNotContain("Diagnóstico", "Expediente", patient.getFirstName());
        return email.body().split("/feedback/")[1].split("\\s")[0];
    }

    @Test void recorridoRealCorreoRespuestaPersistenciaYResumen() throws Exception {
        long charges = countRows("charge");
        Long appointmentId = complete();
        assertThat(appointmentEventTypes(appointmentId)).containsExactly("APPOINTMENT_COMPLETED", "FEEDBACK_REQUESTED");
        assertThat(countRows("patient_feedback")).isEqualTo(1);
        assertThat(countRows("communication")).isEqualTo(1);
        assertThat(countRows("charge")).isEqualTo(charges);
        String token = emailToken();
        assertThat(token).hasSize(43);
        var invitation = feedbackRepository.findByAppointmentId(appointmentId).orElseThrow();
        assertThat(invitation.getTokenHash()).hasSize(64).isNotEqualTo(token);
        assertThat(Duration.between(invitation.getCreatedAt(), invitation.getExpiresAt())).isEqualTo(Duration.ofDays(7));
        mockMvc.perform(get(link(token))).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("AVAILABLE"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mockMvc.perform(post(link(token)).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":5,\"comment\":\"Excelente atención\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RESPONDED"));
        assertThat(feedbackRepository.findByAppointmentId(appointmentId).orElseThrow().getComment()).isEqualTo("Excelente atención");
        mockMvc.perform(get("/api/feedback").header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1)).andExpect(jsonPath("$.average").value(5))
                .andExpect(jsonPath("$.content[0].rating").value(5)).andExpect(jsonPath("$.content[0].tokenHash").doesNotExist())
                .andExpect(jsonPath("$.content[0].appointmentId").doesNotExist());
    }

    @Test void repeticionDelCierreYEventoNoDuplicaInvitacionNiCorreo() throws Exception {
        Long id = complete();
        mockMvc.perform(completeRequest(id).header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isConflict());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> feedback.request(
                new ConsultationCompleted(id, patient.getId(), 1, dentistUser.getId(), TestTime.now())));
        assertThat(countRows("patient_feedback")).isEqualTo(1);
        assertThat(countRows("communication")).isEqualTo(1);
        assertThat(appointmentEventTypes(id)).containsExactly("APPOINTMENT_COMPLETED", "FEEDBACK_REQUESTED");
        emailToken();
        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(sender.sentCount()).isEqualTo(1);
    }

    @Test void tokenExpiraExactamenteALosSieteDias() throws Exception {
        complete(); String token = emailToken();
        TestTime.advance(Duration.ofDays(7));
        mockMvc.perform(get(link(token))).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("EXPIRED"));
        mockMvc.perform(post(link(token)).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":3}"))
                .andExpect(status().isGone()).andExpect(jsonPath("$.state").value("EXPIRED"));
        assertThat(feedbackRepository.findAll().getFirst().getRating()).isNull();
    }

    @Test void tokenInvalidoNoEnumeraNiExponeInformacion() throws Exception {
        String token = "z".repeat(43);
        mockMvc.perform(get(link(token))).andExpect(status().isOk()).andExpect(content().json("{\"state\":\"INVALID\"}"));
        mockMvc.perform(post(link(token)).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":4}"))
                .andExpect(status().isGone()).andExpect(content().json("{\"state\":\"INVALID\"}"));
    }

    @Test void respuestaInmutableYComentarioOpcional() throws Exception {
        complete(); String token = emailToken();
        mockMvc.perform(post(link(token)).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":4}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(link(token)).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":1,\"comment\":\"Cambio\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.state").value("USED"));
        mockMvc.perform(get(link(token))).andExpect(jsonPath("$.state").value("USED"));
        var saved = feedbackRepository.findAll().getFirst();
        assertThat(saved.getRating()).isEqualTo(4); assertThat(saved.getComment()).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"0", "6", "1.5", "\"5\"", "null", "999999999999999999999999999999"})
    void calificacionEstricta(String rating) throws Exception {
        complete(); String token = emailToken();
        String body = mockMvc.perform(post(link(token)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":" + rating + "}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(token, "path", "appointment");
        assertThat(feedbackRepository.findAll().getFirst().getRating()).isNull();
    }

    @Test void limitaComentarioEnApiYMotor() throws Exception {
        Long id = complete(); String token = emailToken();
        mockMvc.perform(post(link(token)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(java.util.Map.of("rating", 5, "comment", "x".repeat(2001)))))
                .andExpect(status().isBadRequest());
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE patient_feedback SET rating=0, status='RESPONDED', responded_at=created_at WHERE appointment_id=?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO patient_feedback(appointment_id,token_hash,created_at,expires_at) SELECT appointment_id,repeat('a',64),created_at,expires_at FROM patient_feedback WHERE appointment_id=?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void concurrenciaDeRespuestaSoloUnaGana() throws Exception {
        complete(); String token = emailToken();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<String> answer = () -> { start.await(); return feedback.respond(token, 5, null); };
            Future<String> a = executor.submit(answer), b = executor.submit(answer); start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("RESPONDED", "USED");
        }
    }

    @Test void ownerGlobalDentistaPropioAjeno404YRolesDenegados() throws Exception {
        complete(); String token = emailToken(); feedback.respond(token, 5, "Privado");
        Long id = feedbackRepository.findAll().getFirst().getId();
        var otherDentist = newUser(Role.DENTIST); seedDentistFor(otherDentist);
        for (var actor : List.of(owner, dentistUser)) {
            mockMvc.perform(get("/api/feedback").header("Authorization", bearer(loginToken(actor))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
            mockMvc.perform(get("/api/feedback/{id}", id).header("Authorization", bearer(loginToken(actor))))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/feedback?dentistId=" + dentist.getId()).header("Authorization", bearer(loginToken(otherDentist))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0)).andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.average").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(get("/api/feedback/{id}", id).header("Authorization", bearer(loginToken(otherDentist))))
                .andExpect(status().isNotFound());
        for (var actor : List.of(receptionist, patientUser)) {
            mockMvc.perform(get("/api/feedback").header("Authorization", bearer(loginToken(actor)))).andExpect(status().isForbidden());
            mockMvc.perform(get("/api/feedback/{id}", id).header("Authorization", bearer(loginToken(actor)))).andExpect(status().isForbidden());
            // Token autoriza, aunque haya una sesión con un rol sin acceso a los listados.
            mockMvc.perform(get(link(token)).header("Authorization", bearer(loginToken(actor))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("USED"));
        }
        mockMvc.perform(get("/api/feedback")).andExpect(status().isUnauthorized());
    }

    @Test void cancelacionAusenciaHistoricasYContactoAusente() throws Exception {
        Long cancelled = appointment(9);
        mockMvc.perform(post("/api/appointments/{id}/cancel", cancelled).header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk());
        assertThat(countRows("patient_feedback")).isZero();
        Long absent = appointment(10);
        TestTime.advance(Duration.between(TestTime.now(), at(MARTES, 12, 0)));
        mockMvc.perform(post("/api/appointments/{id}/no-show", absent).header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk());
        assertThat(countRows("patient_feedback")).isZero();
        var historical = appointmentRepository.findById(appointment(10)).orElseThrow();
        historical.complete(TestTime.now()); appointmentRepository.saveAndFlush(historical);
        assertThat(countRows("patient_feedback")).isZero(); // Sin backfill.
        var noContact = seedPatientWithoutChannel();
        Long id = appointmentRepository.saveAndFlush(Appointment.create(noContact.getId(), dentist.getId(), owner.getId(),
                at(MARTES, 11, 0), at(MARTES, 12, 0), 60, null, null)).getId();
        mockMvc.perform(completeRequest(id).header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject("SELECT resolution FROM communication_intention WHERE appointment_id=? AND event_type='FEEDBACK_REQUESTED'", String.class, id))
                .isEqualTo("NO_CONTACT_CHANNEL");
    }

    @Test void falloDeCorreoNoRevierteCierreYRetryNoDuplicaFila() throws Exception {
        Long id = complete(); sender.reset(); sender.alwaysFail();
        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(appointmentRepository.findById(id).orElseThrow().getStatus().name()).isEqualTo("COMPLETED");
        assertThat(countRows("patient_feedback")).isEqualTo(1); assertThat(countRows("communication")).isEqualTo(1);
        sender.succeed(); TestTime.advance(Duration.ofMinutes(2));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1); assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(sender.sentCount()).isEqualTo(1);
    }

    @Test void falloAlEncolarRevierteCierreEventoEInvitacionAtomicamente() throws Exception {
        Long id = appointment(9);
        jdbcTemplate.execute("ALTER TABLE patient_feedback ADD CONSTRAINT feedback_test_fault CHECK (false)");
        try {
            mockMvc.perform(completeRequest(id).header("Authorization", bearer(loginToken(dentistUser))))
                    .andExpect(status().isConflict());
            assertThat(appointmentRepository.findById(id).orElseThrow().getStatus().name()).isEqualTo("SCHEDULED");
            assertThat(countRows("patient_feedback")).isZero();
            assertThat(countRows("communication")).isZero();
            assertThat(countRows("appointment_event")).isZero();
        } finally { jdbcTemplate.execute("ALTER TABLE patient_feedback DROP CONSTRAINT feedback_test_fault"); }
    }

    @Test void dosDispatchersNoDuplicanCorreoYNoSeEnvianInvitacionesVencidas() throws Exception {
        complete(); sender.reset(); sender.setSendDelayMillis(100);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Integer> task = () -> { start.await(); return dispatcher.dispatchOnce(); };
            var a = executor.submit(task); var b = executor.submit(task); start.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS) + b.get(20, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(sender.sentCount()).isEqualTo(1);
        }
        Long id = appointment(10);
        mockMvc.perform(completeRequest(id).header("Authorization", bearer(loginToken(dentistUser))))
                .andExpect(status().isOk());
        TestTime.advance(Duration.ofDays(7));
        assertThat(dispatcher.dispatchOnce()).isZero(); assertThat(sender.sentCount()).isEqualTo(1);
    }

    private String link(String token) { return "/api/feedback/invitations/" + token; }

    @Test void metodosNoPublicosYCookiesNoFiltranTokenNiAmplianAcceso() throws Exception {
        complete(); String token = emailToken();
        // No depende de cookie ni de CSRF, incluso si el navegador conserva una cookie de sesión.
        mockMvc.perform(post(link(token)).cookie(new jakarta.servlet.http.Cookie("dentalclinic_refresh", "synthetic"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":5}"))
                .andExpect(status().isOk());
        String body = mockMvc.perform(put(link(token))).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(token);
    }

    @Test void cierresConcurrentesSoloUnaInvitacionYEvento() throws Exception {
        Long id = appointment(9);
        var actor = new com.dentalclinic.identity.infrastructure.security.AuthenticatedUser(dentistUser.getId(), Role.DENTIST);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<String> close = () -> {
                start.await();
                try { appointments.complete(id, actor, 1, java.util.UUID.randomUUID().toString()); return "COMPLETED"; }
                catch (com.dentalclinic.scheduling.domain.ScheduleConflictException expected) { return "CONFLICT"; }
            };
            var a = executor.submit(close); var b = executor.submit(close); start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("COMPLETED", "CONFLICT");
        }
        assertThat(countRows("patient_feedback")).isEqualTo(1);
        assertThat(countRows("communication")).isEqualTo(1);
        assertThat(appointmentEventTypes(id)).containsExactly("APPOINTMENT_COMPLETED", "FEEDBACK_REQUESTED");
    }

    @Test void noPuedeSolicitarseNiResponderseFeedbackDeCitaNoCompletada() {
        Long id = appointment(9);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> feedback.request(
                new ConsultationCompleted(id, patient.getId(), 1, dentistUser.getId(), TestTime.now()))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(countRows("patient_feedback")).isZero();
        // Defensa también al consumir: ni una invitación introducida manualmente habilita responder.
        String token = "x".repeat(43);
        feedbackRepository.saveAndFlush(com.dentalclinic.feedback.domain.PatientFeedback.request(id,
                new com.dentalclinic.identity.infrastructure.token.TokenHasher().hash(token), TestTime.now()));
        assertThat(feedback.state(token)).isEqualTo("INVALID");
        assertThat(feedback.respond(token, 5, null)).isEqualTo("INVALID");
        assertThat(feedbackRepository.findAll().getFirst().getRating()).isNull();
    }
}

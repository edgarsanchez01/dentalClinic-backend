package com.dentalclinic.appointment.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.dentalclinic.appointment.infrastructure.repository.AppointmentEventRepository;
import com.dentalclinic.appointment.infrastructure.repository.AppointmentRepository;
import com.dentalclinic.appointment.infrastructure.repository.CommunicationIntentionRepository;
import com.dentalclinic.appointment.infrastructure.repository.CommunicationRepository;
import com.dentalclinic.appointment.infrastructure.repository.HttpIdempotencyRepository;
import com.dentalclinic.appointment.infrastructure.repository.NotificationRepository;
import com.dentalclinic.clinic.domain.Clinic;
import com.dentalclinic.clinic.domain.Dentist;
import com.dentalclinic.clinic.infrastructure.repository.ClinicRepository;
import com.dentalclinic.clinic.infrastructure.repository.DentistRepository;
import com.dentalclinic.identity.IdentityIntegrationTestSupport;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.TestTimeConfig;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.infrastructure.repository.InvitationRepository;
import com.dentalclinic.identity.infrastructure.repository.PasswordResetTokenRepository;
import com.dentalclinic.identity.infrastructure.repository.RefreshSessionRepository;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;
import com.dentalclinic.patient.domain.Patient;
import com.dentalclinic.patient.infrastructure.repository.PatientRepository;
import com.dentalclinic.scheduling.domain.ClinicSchedule;
import com.dentalclinic.scheduling.domain.DentistSchedule;
import com.dentalclinic.scheduling.infrastructure.repository.ClinicScheduleRepository;
import com.dentalclinic.scheduling.infrastructure.repository.DentistScheduleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Base de los tests de integración de citas (F6.D).
 *
 * <p>A diferencia del resto de la suite —que corre con {@code ddl-auto=create-drop} a partir de las
 * entidades— esta base <strong>aplica el DDL real V001..V019</strong> (todas las fases
 * implementadas, porque Hibernate valida el modelo completo) y deja Hibernate en
 * {@code validate}, sobre una base efímera dedicada ({@code dentalclinic_appointments}). Es
 * deliberado: varias reglas aprobadas de Fase 6 son garantía de <strong>motor</strong>, no de
 * aplicación (EXCLUDE anti-doble-booking A-14, índices parciales UNIQUE de llegada/comienzo
 * D-F6-24.b, FK compuestas O-1, {@code ci_uniq}, {@code http_idempotency} UNIQUE). Probarlas sobre
 * un esquema sintético generado por Hibernate no probaría nada.
 *
 * <p>El emisor de correo falso ({@link FakeEmailSenderConfiguration}) se registra aquí, en la base
 * compartida, para que todos los tests de citas reutilicen un único contexto y el DDL no se
 * reaplique sobre la misma base efímera.
 */
@AutoConfigureMockMvc
@Import(FakeEmailSenderConfiguration.class)
@TestPropertySource(properties = {
        "dentalclinic.communication.email.enabled=false",
        "dentalclinic.communication.dispatch.enabled=false",
        "dentalclinic.appointment.reminder.enabled=false",
        "dentalclinic.appointment.reminder.email-delivery-enabled=false",
        "spring.datasource.url=jdbc:tc:postgresql:16:///dentalclinic_appointments",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=file:../db/schema/V001__baseline.sql,"
                + "file:../db/schema/V002__identity_access.sql,"
                + "file:../db/schema/V003__clinic_onboarding.sql,"
                + "file:../db/schema/V004__patients_clinical.sql,"
                + "file:../db/schema/V005__scheduling.sql,"
                + "file:../db/schema/V006__appointments.sql,"
                + "file:../db/schema/V007__service_catalog.sql,"
                + "file:../db/schema/V008__appointment_service_snapshot.sql,"
                + "file:../db/schema/V009__treatments.sql,"
                + "file:../db/schema/V010__odontogram.sql,"
                + "file:../db/schema/V011__odontogram_surfaces.sql,"
                + "file:../db/schema/V012__treatment_evolution.sql,"
                + "file:../db/schema/V013__clinical_appointment_context.sql,"
                + "file:../db/schema/V014__clinical_corrections.sql,"
                + "file:../db/schema/V015__odontogram_history_context.sql,"
                + "file:../db/schema/V016__financial_module.sql,"
                + "file:../db/schema/V017__patient_feedback.sql,"
                + "file:../db/schema/V018__identity_email_outbox.sql,"
                + "file:../db/schema/V019__patient_medical_intake.sql,"
                + "file:../db/schema/V020__appointment_checkout.sql"
})
public abstract class AppointmentIntegrationTestSupport extends IdentityIntegrationTestSupport {

    protected static final String PASSWORD = "ClaveCitas-2026";
    /** Martes 13 de octubre de 2026: dentro del horizonte y en horario hábil (UTC). */
    protected static final LocalDate MARTES = LocalDate.of(2026, 10, 13);
    protected static final LocalDate MIERCOLES = LocalDate.of(2026, 10, 14);

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected UserRepository userRepository;
    @Autowired
    protected PatientRepository patientRepository;
    @Autowired
    protected DentistRepository dentistRepository;
    @Autowired
    protected ClinicRepository clinicRepository;
    @Autowired
    protected ClinicScheduleRepository clinicScheduleRepository;
    @Autowired
    protected DentistScheduleRepository dentistScheduleRepository;
    @Autowired
    protected PasswordEncoder passwordEncoder;
    @Autowired
    protected RefreshSessionRepository refreshSessionRepository;
    @Autowired
    protected InvitationRepository invitationRepository;
    @Autowired
    protected PasswordResetTokenRepository resetTokenRepository;
    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected AppointmentRepository appointmentRepository;
    @Autowired
    protected AppointmentEventRepository appointmentEventRepository;
    @Autowired
    protected CommunicationRepository communicationRepository;
    @Autowired
    protected CommunicationIntentionRepository communicationIntentionRepository;
    @Autowired
    protected NotificationRepository notificationRepository;
    @Autowired
    protected com.dentalclinic.catalog.application.ServiceCatalogService catalog;
    @Autowired
    protected com.dentalclinic.catalog.infrastructure.repository.ServiceCatalogRepository catalogRepository;
    private final Map<Integer, Long> testServices = new java.util.concurrent.ConcurrentHashMap<>();
    @Autowired
    protected HttpIdempotencyRepository httpIdempotencyRepository;
    @Autowired protected com.dentalclinic.feedback.application.FeedbackService feedback;

    protected Clinic clinic;
    protected User owner;
    protected User receptionist;
    protected User dentistUser;
    protected Dentist dentist;
    /** El OWNER onboarding lleva siempre perfil de dentista (Architecture AD/§7.4). */
    protected Dentist ownerDentist;
    protected User patientUser;
    protected Patient patient;

    @BeforeEach
    void seedAppointmentFixture() {
        TestTime.reset();
        catalogRepository.deleteAll();
        testServices.clear();
        refreshSessionRepository.deleteAll();
        resetTokenRepository.deleteAll();
        invitationRepository.deleteAll();
        userRepository.deleteAll();

        clinic = Clinic.create("Clínica Dental", "UTC", null, null, null, null, null, null, null, null);
        clinic.updateBookingPolicy(90, 0, 12, TestTime.now());
        clinic = clinicRepository.saveAndFlush(clinic);
        for (int day = 1; day <= 5; day++) {
            clinicScheduleRepository.saveAndFlush(
                    ClinicSchedule.create(clinic.getId(), day, LocalTime.of(9, 0), LocalTime.of(18, 0)));
        }

        owner = newUser(Role.OWNER);
        ownerDentist = seedDentistFor(owner);
        receptionist = newUser(Role.RECEPTIONIST);
        dentistUser = newUser(Role.DENTIST);
        dentist = seedDentistFor(dentistUser);

        patientUser = newUser(Role.PATIENT);
        patient = patientRepository.saveAndFlush(Patient.createForUser(patientUser.getId(), "Paciente",
                "Uno", patientUser.getEmail(), "+34600000002", null, null));
    }

    // ---------------------------------------------------------------- fixtures

    protected User newUser(Role role) {
        int n = COUNTER.incrementAndGet();
        return userRepository.saveAndFlush(
                User.create(role.name().toLowerCase() + n + "@dc.test", passwordEncoder.encode(PASSWORD), role));
    }

    /** Perfil profesional de un usuario con horario hábil (lun–vie 09:00–18:00). */
    protected Dentist seedDentistFor(User user) {
        Dentist d = dentistRepository.saveAndFlush(
                Dentist.create(user.getId(), "Dentia", "Perfil", "+34600000000", null, null, null, null));
        for (int day = 1; day <= 5; day++) {
            dentistScheduleRepository.saveAndFlush(
                    DentistSchedule.create(d.getId(), day, LocalTime.of(9, 0), LocalTime.of(18, 0)));
        }
        return d;
    }

    /** Paciente administrativo sin cuenta ni correo: escenario NO_CONTACT_CHANNEL (D-F6-01). */
    protected Patient seedPatientWithoutChannel() {
        return patientRepository.saveAndFlush(
                Patient.create("Sin", "Canal", null, null, null, null));
    }

    protected void setBookingPolicy(int maxAdvanceDays, int minLeadHours, int cancellationHours) {
        clinic.updateBookingPolicy(maxAdvanceDays, minLeadHours, cancellationHours, TestTime.now());
        clinicRepository.saveAndFlush(clinic);
    }

    protected void setTimezone(String timezone) {
        clinic.update(clinic.getName(), timezone, clinic.getDescription(), clinic.getLogoUrl(),
                clinic.getAddress(), clinic.getPhone(), clinic.getEmail(), clinic.getPublicInfo(),
                clinic.getBrandColor(), clinic.getSocialNetworks(), TestTime.now());
        clinicRepository.saveAndFlush(clinic);
    }

    // ------------------------------------------------------------------ HTTP

    protected String loginToken(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(user.getEmail(), PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    protected String bearer(String token) {
        return "Bearer " + token;
    }

    /** New clinical contract; each use is a distinct operation unless a test explicitly reuses its key. */
    protected org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder completeRequest(Long id) {
        return post("/api/appointments/{id}/complete", id).header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedOccurrenceVersion\":"
                        + appointmentRepository.findById(id).orElseThrow().getOccurrenceVersion() + "}");
    }

    protected String createBody(Long patientId, Long dentistId, Instant startAt, Instant endAt)
            throws Exception {
        // Explicit isolated fixture only; production never seeds a service or infers one.
        int duration = Math.max(1, (int) java.time.Duration.between(startAt, endAt).toMinutes());
        Long serviceId = testServices.computeIfAbsent(duration, value -> catalog.create(
                new com.dentalclinic.catalog.application.ServiceCatalogService.ServiceData(
                        "Servicio de prueba " + value, null, java.math.BigDecimal.ONE, value)).getId());
        return objectMapper.writeValueAsString(Map.of(
                "serviceId", serviceId,
                "patientId", patientId,
                "dentistId", dentistId,
                "startAt", startAt.toString(),
                "endAt", endAt.toString(),
                "reason", "Control"));
    }

    /** Instante UTC de un día a una hora dada (la clínica por defecto está en UTC). */
    protected Instant at(LocalDate day, int hour, int minute) {
        return day.atTime(hour, minute).atZone(ZoneId.of("UTC")).toInstant();
    }

    protected long countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    protected List<String> appointmentEventTypes(Long appointmentId) {
        return jdbcTemplate.queryForList(
                "SELECT event_type FROM appointment_event WHERE appointment_id = ? ORDER BY id",
                String.class, appointmentId);
    }
}

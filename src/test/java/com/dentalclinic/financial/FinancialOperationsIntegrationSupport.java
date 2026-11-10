package com.dentalclinic.financial;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import com.dentalclinic.financial.application.ChargeFinancialStateService;
import com.dentalclinic.financial.application.FinancialOperations;
import com.dentalclinic.financial.application.command.ChargeItemData;
import com.dentalclinic.financial.application.command.ConfirmChargeCommand;
import com.dentalclinic.financial.application.command.RegisterPaymentCommand;
import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.FinancialMovement;
import com.dentalclinic.financial.domain.PaymentMethod;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/** Contexto exclusivo de 2B, con DDL real y fixtures sinteticas; nunca usa :5434. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=always",
        "dentalclinic.communication.email.enabled=false",
        "dentalclinic.communication.dispatch.enabled=false",
        "dentalclinic.appointment.reminder.enabled=false",
        "dentalclinic.appointment.reminder.email-delivery-enabled=false",
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
                + "file:../db/schema/V020__appointment_checkout.sql,"
                + "file:../db/schema/V021__financial_intent.sql"
})
public abstract class FinancialOperationsIntegrationSupport {
    private static final String URL = "jdbc:tc:postgresql:16:///dentalclinic_finance_operations";

    @DynamicPropertySource
    protected static void isolatedDatabase(DynamicPropertyRegistry registry) {
        String isolatedUrl = URL + "_" + UUID.randomUUID().toString().replace("-", "");
        registry.add("spring.datasource.url", () -> isolatedUrl);
        registry.add("spring.datasource.driver-class-name",
                () -> "org.testcontainers.jdbc.ContainerDatabaseDriver");
    }

    @Autowired protected FinancialOperations operations;
    @Autowired protected ChargeFinancialStateService states;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactions;
    @Autowired private Environment environment;

    protected AuthenticatedUser owner;
    protected AuthenticatedUser reception;
    protected AuthenticatedUser dentist;
    protected AuthenticatedUser otherDentist;
    protected AuthenticatedUser patientActor;
    protected Long patientId;
    protected Long otherPatientId;
    protected Long dentistId;
    protected Long otherDentistId;
    protected Long ownAppointmentId;
    protected Long otherAppointmentId;

    @BeforeEach
    protected void seedIsolatedFixture() throws Exception {
        assertThat(environment.getProperty("spring.datasource.url")).startsWith(URL + "_");
        assertThat(environment.getProperty("spring.datasource.driver-class-name"))
                .isEqualTo("org.testcontainers.jdbc.ContainerDatabaseDriver");
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).doesNotContain(":5434/");
            assertThat(connection.getMetaData().getDatabaseProductVersion()).startsWith("16.");
        }
        for (String table : List.of("financial_intent", "appointment_checkout", "http_idempotency", "financial_movement",
                "charge_item", "charge", "appointment", "service_catalog_item", "dentist", "patient",
                "users")) {
            jdbc.update("DELETE FROM " + table); // Solo nombres constantes, exclusivamente en Testcontainers.
        }
        owner = user("owner", Role.OWNER);
        reception = user("reception", Role.RECEPTIONIST);
        dentist = user("dentist", Role.DENTIST);
        otherDentist = user("other", Role.DENTIST);
        patientActor = user("patient", Role.PATIENT);
        dentistId = professional(dentist);
        otherDentistId = professional(otherDentist);
        patientId = patient("Uno");
        otherPatientId = patient("Dos");
        ownAppointmentId = appointment(patientId, dentistId);
        otherAppointmentId = appointment(patientId, otherDentistId);
    }

    private AuthenticatedUser user(String name, Role role) {
        Long id = jdbc.queryForObject("INSERT INTO users (email, password_hash, role, active, created_at) "
                + "VALUES (?, 'test-not-a-real-password', ?, TRUE, now()) RETURNING id",
                Long.class, name + "@financial.test", role.name());
        return new AuthenticatedUser(id, role);
    }

    private Long professional(AuthenticatedUser actor) {
        return jdbc.queryForObject("INSERT INTO dentist (user_id, first_name, last_name, phone) "
                + "VALUES (?, 'Dentista', 'Prueba', '600000201') RETURNING id", Long.class, actor.userId());
    }

    private Long patient(String name) {
        return jdbc.queryForObject("INSERT INTO patient (first_name, last_name, phone) "
                + "VALUES (?, 'Prueba', '600000202') RETURNING id", Long.class, name);
    }

    protected Long appointment(Long patient, Long professional) {
        return jdbc.queryForObject("INSERT INTO appointment (patient_id, dentist_id, created_by_user_id, "
                + "start_at, end_at, duration_minutes, status, completed_at) VALUES (?, ?, ?, "
                + "'2026-10-14T00:00:00Z', '2026-10-14T01:00:00Z', 60, 'COMPLETED', "
                + "'2026-10-14T01:00:00Z') RETURNING id", Long.class, patient, professional, owner.userId());
    }

    protected ConfirmChargeCommand command(Long appointment, Long professional, String amount, String key) {
        return new ConfirmChargeCommand(patientId, appointment,
                List.of(item(professional, "Concepto acordado", 1, amount)), "Nota administrativa", key);
    }

    protected static ChargeItemData item(Long professional, String description, int quantity, String price) {
        return new ChargeItemData(null, professional, description, quantity, new BigDecimal(price));
    }

    protected Charge confirmed(String amount) {
        return operations.confirmCharge(command(null, null, amount, key()), owner);
    }

    protected FinancialMovement payment(Charge charge, String amount, String key) {
        return operations.registerPayment(new RegisterPaymentCommand(charge.getId(), PaymentMethod.CASH,
                new BigDecimal(amount), null, key), owner);
    }

    protected long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    protected static String key() {
        return UUID.randomUUID().toString();
    }
}

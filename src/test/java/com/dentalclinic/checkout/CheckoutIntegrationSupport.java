package com.dentalclinic.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Contexto de integracion de la identidad durable del cierre (UX-05B.1, Etapa 3.2).
 *
 * <p>Usa exclusivamente PostgreSQL 16 efimero de Testcontainers, con el DDL real V001..V020 y
 * {@code ddl-auto=validate}. Nunca apunta a la base persistente {@code 127.0.0.1:5434}.
 */
@SpringBootTest
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
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
public abstract class CheckoutIntegrationSupport {

    private static final String URL = "jdbc:tc:postgresql:16:///dentalclinic_checkout";

    @DynamicPropertySource
    protected static void isolatedDatabase(DynamicPropertyRegistry registry) {
        String isolatedUrl = URL + "_" + java.util.UUID.randomUUID().toString().replace("-", "");
        registry.add("spring.datasource.url", () -> isolatedUrl);
        registry.add("spring.datasource.driver-class-name",
                () -> "org.testcontainers.jdbc.ContainerDatabaseDriver");
    }

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected PlatformTransactionManager transactions;
    @Autowired private Environment environment;

    protected Long ownerId;
    protected Long receptionId;
    protected Long dentistId;
    protected Long otherDentistId;
    protected Long patientId;
    protected Long otherPatientId;
    protected Long appointmentId;
    protected Long chargeId;

    @BeforeEach
    protected void seedFixture() throws Exception {
        assertThat(environment.getProperty("spring.datasource.url")).startsWith(URL + "_");
        assertThat(environment.getProperty("spring.datasource.driver-class-name"))
                .isEqualTo("org.testcontainers.jdbc.ContainerDatabaseDriver");
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).doesNotContain(":5434/");
            assertThat(connection.getMetaData().getDatabaseProductVersion()).startsWith("16.");
        }
        for (String table : java.util.List.of("appointment_checkout", "http_idempotency",
                "financial_movement", "charge_item", "charge", "appointment", "service_catalog_item",
                "dentist", "patient", "users")) {
            if (table.equals("appointment_checkout") && !Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT to_regclass('appointment_checkout') IS NOT NULL", Boolean.class))) continue;
            jdbc.update("DELETE FROM " + table); // Solo nombres constantes, en Testcontainers.
        }
        ownerId = user("owner", "OWNER");
        receptionId = user("reception", "RECEPTIONIST");
        dentistId = dentist(user("dentist", "DENTIST"), "600000301");
        otherDentistId = dentist(user("other", "DENTIST"), "600000302");
        patientId = patient("Uno", "600000303");
        otherPatientId = patient("Dos", "600000304");
        appointmentId = appointment(patientId, dentistId, "COMPLETED");
        chargeId = charge(patientId, appointmentId, "100.00");
    }

    private Long user(String name, String role) {
        return jdbc.queryForObject("INSERT INTO users (email, password_hash, role, active, created_at) "
                + "VALUES (?, 'test-not-a-real-password', ?, TRUE, now()) RETURNING id",
                Long.class, name + "@checkout.test", role);
    }

    private Long dentist(Long userId, String phone) {
        return jdbc.queryForObject("INSERT INTO dentist (user_id, first_name, last_name, phone) "
                + "VALUES (?, 'Dentista', 'Prueba', ?) RETURNING id", Long.class, userId, phone);
    }

    private Long patient(String name, String phone) {
        return jdbc.queryForObject("INSERT INTO patient (first_name, last_name, phone) "
                + "VALUES (?, 'Prueba', ?) RETURNING id", Long.class, name, phone);
    }

    protected Long appointment(Long patient, Long professional, String status) {
        return jdbc.queryForObject("INSERT INTO appointment (patient_id, dentist_id, "
                + "created_by_user_id, start_at, end_at, duration_minutes, status, completed_at) "
                + "VALUES (?, ?, ?, '2026-10-14T00:00:00Z', '2026-10-14T01:00:00Z', 60, ?, "
                + "CASE WHEN ? = 'COMPLETED' THEN '2026-10-14T01:00:00Z'::timestamptz ELSE NULL END) "
                + "RETURNING id", Long.class, patient, professional, ownerId, status, status);
    }

    protected Long charge(Long patient, Long appointment, String total) {
        return jdbc.queryForObject("INSERT INTO charge (patient_id, appointment_id, total_amount, "
                + "confirmed_at, confirmed_by_user_id) VALUES (?, ?, ?::numeric, now(), ?) RETURNING id",
                Long.class, patient, appointment, new BigDecimal(total), ownerId);
    }

    protected Long payment(Long charge, Long patient, String amount) {
        return jdbc.queryForObject("INSERT INTO financial_movement (charge_id, patient_id, type, "
                + "direction, payment_method, amount, performed_by_user_id, performed_at) "
                + "VALUES (?, ?, 'PAYMENT', 'I', 'CASH', ?::numeric, ?, now()) RETURNING id",
                Long.class, charge, patient, new BigDecimal(amount), ownerId);
    }

    protected Long reversal(Long payment, Long charge, Long patient, String amount) {
        return jdbc.queryForObject("INSERT INTO financial_movement (charge_id, patient_id, type, "
                + "direction, amount, reason, reverses_movement_id, performed_by_user_id, performed_at) "
                + "VALUES (?, ?, 'REVERSAL', 'O', ?::numeric, 'Correccion', ?, ?, now()) RETURNING id",
                Long.class, charge, patient, new BigDecimal(amount), payment, ownerId);
    }

    protected long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}

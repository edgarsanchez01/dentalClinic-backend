package com.dentalclinic.identity.integration;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.dentalclinic.appointment.integration.FakeEmailSenderConfiguration;
import com.dentalclinic.appointment.integration.RecordingEmailSender;
import com.dentalclinic.identity.IdentityIntegrationTestSupport;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.application.*;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.infrastructure.repository.*;
import com.dentalclinic.identity.infrastructure.token.IdentityLinkTokens;
import com.dentalclinic.identity.infrastructure.token.TokenHasher;

/** Flujos 8B.3 con DDL real, PostgreSQL efímero y emisor falso; nunca SMTP. */
@AutoConfigureMockMvc
@Import(FakeEmailSenderConfiguration.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:16:///dentalclinic_identity_flows",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=file:../db/schema/V001__baseline.sql,"
                + "file:../db/schema/V002__identity_access.sql,file:../db/schema/V003__clinic_onboarding.sql,"
                + "file:../db/schema/V004__patients_clinical.sql,file:../db/schema/V005__scheduling.sql,"
                + "file:../db/schema/V006__appointments.sql,file:../db/schema/V007__service_catalog.sql,"
                + "file:../db/schema/V008__appointment_service_snapshot.sql,file:../db/schema/V009__treatments.sql,"
                + "file:../db/schema/V010__odontogram.sql,file:../db/schema/V011__odontogram_surfaces.sql,"
                + "file:../db/schema/V012__treatment_evolution.sql,file:../db/schema/V013__clinical_appointment_context.sql,"
                + "file:../db/schema/V014__clinical_corrections.sql,file:../db/schema/V015__odontogram_history_context.sql,"
                + "file:../db/schema/V016__financial_module.sql,file:../db/schema/V017__patient_feedback.sql,"
                + "file:../db/schema/V018__identity_email_outbox.sql,"
                + "file:../db/schema/V019__patient_medical_intake.sql,"
                + "file:../db/schema/V020__appointment_checkout.sql",
        "dentalclinic.identity.abuse.reset-max-per-account=2",
        "dentalclinic.identity.abuse.staff-max-per-account=3",
        "dentalclinic.identity.abuse.reset-max-per-ip=3"
})
abstract class IdentityLinkFlowTestSupport extends IdentityIntegrationTestSupport {
    protected static final String PASSWORD = "Password-Original-2026";
    protected static final String NEW_PASSWORD = "Password-Nueva-2026";
    @Autowired protected StaffInvitationService staff;
    @Autowired protected PasswordService passwords;
    @Autowired protected InvitationActivationService activation;
    @Autowired protected SessionService sessions;
    @Autowired protected UserRepository users;
    @Autowired protected InvitationRepository invitations;
    @Autowired protected PasswordResetTokenRepository resets;
    @Autowired protected RefreshSessionRepository refresh;
    @Autowired protected IdentityEmailOutboxRepository outbox;
    @Autowired protected IdentityEmailDispatcher dispatcher;
    @Autowired protected RecordingEmailSender sender;
    @Autowired protected IdentityLinkTokens tokens;
    @Autowired protected TokenHasher hasher;
    @Autowired protected PasswordEncoder encoder;
    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanFlow() {
        TestTime.reset();
        sender.reset();
        invitations.deleteAll();
        resets.deleteAll();
        refresh.deleteAll();
        users.deleteAll();
    }

    protected User active(String email, Role role) {
        return users.saveAndFlush(User.create(email, encoder.encode(PASSWORD), role));
    }

    protected String lastToken() {
        var messages = sender.messages();
        String body = messages.get(messages.size() - 1).body();
        String raw = body.substring(body.indexOf("?token=") + 7).split("\\s", 2)[0];
        org.assertj.core.api.Assertions.assertThat(raw).matches("[A-Za-z0-9_-]{43}");
        return raw;
    }
}

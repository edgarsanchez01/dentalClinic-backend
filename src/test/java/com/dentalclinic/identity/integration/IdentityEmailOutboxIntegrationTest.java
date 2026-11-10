package com.dentalclinic.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.dentalclinic.appointment.application.OutboundEmail;
import com.dentalclinic.appointment.integration.FakeEmailSenderConfiguration;
import com.dentalclinic.appointment.integration.RecordingEmailSender;
import com.dentalclinic.identity.IdentityIntegrationTestSupport;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.application.IdentityEmailClaimService;
import com.dentalclinic.identity.application.IdentityEmailDispatcher;
import com.dentalclinic.identity.application.IdentityEmailComposer;
import com.dentalclinic.identity.application.ClaimedIdentityEmail;
import com.dentalclinic.identity.application.IdentityEmailOutboxService;
import com.dentalclinic.identity.application.IdentityEmailOutboxService.Enqueue;
import com.dentalclinic.identity.domain.IdentityEmailOutbox;
import com.dentalclinic.identity.domain.IdentityEmailPurpose;
import com.dentalclinic.identity.domain.Invitation;
import com.dentalclinic.identity.domain.PasswordResetToken;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.infrastructure.config.IdentityLinkProperties;
import com.dentalclinic.identity.infrastructure.config.IdentityEmailProperties;
import com.dentalclinic.identity.infrastructure.repository.InvitationRepository;
import com.dentalclinic.identity.infrastructure.repository.PasswordResetTokenRepository;
import com.dentalclinic.identity.infrastructure.repository.RefreshSessionRepository;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;
import com.dentalclinic.identity.infrastructure.token.IdentityLinkTokens;

/**
 * F8B.2 · Outbox de enlaces de identidad sobre el DDL real (V001–V018, {@code validate}).
 *
 * <p>El emisor es falso ({@link RecordingEmailSender}): no hay conexión SMTP y ningún test envía
 * correo real. Se ejercitan el encolado idempotente, la invalidación de emisiones anteriores, la
 * reconstrucción y verificación del token, la elegibilidad (consumido/expirado/revocado/hash
 * desajustado), el claim concurrente, el lease, los reintentos/FAILED y la ausencia de token/URL en
 * la base. La outbox de citas ({@code communication}) queda intacta: no la toca esta fase.
 */
@Import(FakeEmailSenderConfiguration.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:16:///dentalclinic_identity_email",
        "spring.jpa.hibernate.ddl-auto=validate",
        "logging.level.com.dentalclinic.identity.application.IdentityEmailDispatcher=WARN",
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
class IdentityEmailOutboxIntegrationTest extends IdentityIntegrationTestSupport {

    @Autowired
    private IdentityEmailDispatcher dispatcher;
    @Autowired
    private IdentityEmailOutboxService outbox;
    @Autowired
    private IdentityEmailClaimService claims;
    @Autowired
    private RecordingEmailSender sender;
    @Autowired
    private IdentityLinkTokens tokens;
    @Autowired
    private IdentityLinkProperties links;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private InvitationRepository invitationRepository;
    @Autowired
    private PasswordResetTokenRepository resetTokenRepository;
    @Autowired
    private RefreshSessionRepository refreshSessionRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private IdentityEmailComposer composer;
    @Autowired
    private IdentityEmailProperties properties;
    @Autowired
    private Clock clock;

    @BeforeEach
    void resetState() {
        TestTime.reset();
        sender.reset();
        jdbc.update("DELETE FROM identity_email_outbox");
        resetTokenRepository.deleteAll();
        refreshSessionRepository.deleteAll();
        invitationRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ---------------------------------------------------------------- encolado

    @Test
    void encoladoEsIdempotentePorClave() {
        User user = createUser("staff1@dc.test", Role.DENTIST);
        Emission emission = staffInvitation(user, TestTime.now().plus(Duration.ofHours(1)));

        IdentityEmailOutbox first = outbox.enqueue(command(user, emission, "key-1"));
        IdentityEmailOutbox second = outbox.enqueue(command(user, emission, "key-1"));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void reemitirInvalidaLaEntregaAnterior() {
        User user = createUser("staff2@dc.test", Role.DENTIST);
        IdentityEmailOutbox first = outbox.enqueue(command(user,
                staffInvitation(user, TestTime.now().plus(Duration.ofHours(1))), "key-a"));
        revokeInvitations(user.getId());
        IdentityEmailOutbox second = outbox.enqueue(command(user,
                staffInvitation(user, TestTime.now().plus(Duration.ofHours(1))), "key-b"));

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(row(first.getId()).get("superseded_at")).isNotNull();
        assertThat(statusOf(second.getId())).isEqualTo("PENDING");
        assertThat(activeCount()).isEqualTo(1);
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(sender.sentCount()).isEqualTo(1);
        assertThat(statusOf(first.getId())).isEqualTo("PENDING");
    }

    // ------------------------------------------------------------- composición

    @Test
    void envioReconstruyeElTokenLoVerificaYEntregaElEnlace() {
        User user = createUser("staff3@dc.test", Role.DENTIST);
        Emission emission = staffInvitation(user, TestTime.now().plus(Duration.ofHours(1)));
        IdentityEmailOutbox row = outbox.enqueue(command(user, emission, "key-3"));

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        assertThat(sender.sentCount()).isEqualTo(1);
        OutboundEmail message = sender.messages().get(0);
        assertThat(message.to()).isEqualTo(user.getEmail());
        String reconstructed = tokens.token(IdentityLinkTokens.Domain.STAFF_INVITATION, user.getId(),
                emission.nonce());
        assertThat(reconstructed).isEqualTo(emission.token());
        assertThat(message.body())
                .contains(links.publicBaseUrl() + "/activate?token=" + reconstructed);
        assertThat(statusOf(row.getId())).isEqualTo("SENT");
        assertThat(attemptsOf(row.getId())).isEqualTo(1);
    }

    @Test
    void envioDeRecuperacionUsaLaRutaDeResetPassword() {
        User user = createUser("staff4@dc.test", Role.PATIENT);
        Emission emission = passwordReset(user, TestTime.now().plus(Duration.ofHours(1)));
        outbox.enqueue(new Enqueue(user.getId(), IdentityEmailPurpose.PASSWORD_RESET,
                user.getEmail(), emission.nonce(), emission.hash(), "key-4"));

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        String reconstructed = tokens.token(IdentityLinkTokens.Domain.PASSWORD_RESET, user.getId(),
                emission.nonce());
        assertThat(sender.messages().get(0).body())
                .contains(links.publicBaseUrl() + "/reset-password?token=" + reconstructed);
    }

    // -------------------------------------------------------------- elegibilidad

    @Test
    void tokenConsumidoNoSeEnvia() {
        User user = createUser("consumed@dc.test", Role.DENTIST);
        Emission emission = staffInvitation(user, TestTime.now().plus(Duration.ofHours(1)));
        Invitation invitation = invitationRepository.findByTokenHash(emission.hash()).orElseThrow();
        invitation.markUsedAt(TestTime.now(), user.getId());
        invitationRepository.saveAndFlush(invitation);

        IdentityEmailOutbox row = outbox.enqueue(command(user, emission, "key-consumed"));

        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(sender.sentCount()).isZero();
        assertThat(statusOf(row.getId())).isEqualTo("PENDING");
        assertThat(row(row.getId()).get("superseded_at")).isNotNull();
    }

    @Test
    void tokenExpiradoNoSeEnvia() {
        User user = createUser("expired@dc.test", Role.DENTIST);
        Emission emission = staffInvitation(user, TestTime.now().minus(Duration.ofHours(1)));
        IdentityEmailOutbox row = outbox.enqueue(command(user, emission, "key-expired"));

        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(sender.sentCount()).isZero();
        assertThat(row(row.getId()).get("superseded_at")).isNotNull();
    }

    @Test
    void tokenRevocadoNoSeEnvia() {
        User user = createUser("revoked@dc.test", Role.DENTIST);
        Emission emission = staffInvitation(user, TestTime.now().plus(Duration.ofHours(1)));
        IdentityEmailOutbox row = outbox.enqueue(command(user, emission, "key-revoked"));
        revokeInvitations(user.getId());

        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(sender.sentCount()).isZero();
        assertThat(row(row.getId()).get("superseded_at")).isNotNull();
    }

    @Test
    void hashDesajustadoPorRotacionDeClaveNoEnviaEnlaceRoto() {
        User user = createUser("rotated@dc.test", Role.DENTIST);
        Emission emission = staffInvitation(user, TestTime.now().plus(Duration.ofHours(1)));
        // Nonce real, pero un hash que no corresponde al token recomponible (como tras rotar la clave).
        IdentityEmailOutbox row = outbox.enqueue(new Enqueue(user.getId(),
                IdentityEmailPurpose.STAFF_INVITATION, user.getEmail(), emission.nonce(),
                "a".repeat(64), "key-rotated"));

        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(sender.sentCount()).isZero();
        assertThat(row(row.getId()).get("superseded_at")).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"consumido", "expirado", "revocado", "sustituido", "inactivo"})
    void recuperacionNoVigenteNuncaSeEnvia(String reason) {
        User user = createUser("reset-invalid@dc.test", Role.PATIENT);
        Emission emission = passwordReset(user, TestTime.now().plus(Duration.ofHours(1)));
        IdentityEmailOutbox row = outbox.enqueue(new Enqueue(user.getId(),
                IdentityEmailPurpose.PASSWORD_RESET, user.getEmail(), emission.nonce(), emission.hash(),
                "reset-invalid"));
        switch (reason) {
            case "consumido" -> jdbc.update("UPDATE password_reset_tokens SET used_at = now() WHERE user_id = ?",
                    user.getId());
            case "expirado" -> TestTime.advance(Duration.ofHours(1));
            case "revocado" -> jdbc.update("DELETE FROM password_reset_tokens WHERE user_id = ?", user.getId());
            case "sustituido" -> {
                jdbc.update("DELETE FROM password_reset_tokens WHERE user_id = ?", user.getId());
                passwordReset(user, TestTime.now().plus(Duration.ofHours(1)));
            }
            case "inactivo" -> {
                user.deactivate();
                userRepository.saveAndFlush(user);
            }
            default -> throw new AssertionError(reason);
        }
        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(sender.sentCount()).isZero();
        assertThat(row(row.getId()).get("superseded_at")).isNotNull();
    }

    @Test
    void destinatarioAjenoALaCuentaNoRecibeElToken() {
        User user = createUser("right@dc.test", Role.DENTIST);
        Emission emission = staffInvitation(user, TestTime.now().plus(Duration.ofHours(1)));
        outbox.enqueue(new Enqueue(user.getId(), IdentityEmailPurpose.STAFF_INVITATION,
                "wrong@dc.test", emission.nonce(), emission.hash(), "wrong-recipient"));
        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(sender.sentCount()).isZero();
    }

    @Test
    void entregaSustituidaDespuesDelClaimNoSeCompone() {
        User user = createUser("superseded-claim@dc.test", Role.DENTIST);
        Emission emission = staffInvitation(user, TestTime.now().plus(Duration.ofHours(1)));
        outbox.enqueue(command(user, emission, "old-claim"));
        ClaimedIdentityEmail old = claims.claim(TestTime.now(), 1, Duration.ofMinutes(5)).get(0);
        // La emisión anterior sigue usable en la tabla: se comprueba también la propia outbox.
        outbox.enqueue(new Enqueue(user.getId(), IdentityEmailPurpose.STAFF_INVITATION,
                user.getEmail(), tokens.newNonce(), "b".repeat(64), "new-claim"));
        assertThat(composer.compose(old)).isEmpty();
        assertThat(sender.sentCount()).isZero();
    }

    // ---------------------------------------------------------- concurrencia

    @Test
    void dosDispatchersConcurrentesNoDuplicanElEnvio() throws Exception {
        User user = createUser("concurrent@dc.test", Role.DENTIST);
        Emission emission = staffInvitation(user, TestTime.now().plus(Duration.ofHours(1)));
        IdentityEmailOutbox row = outbox.enqueue(command(user, emission, "key-concurrent"));
        sender.setSendDelayMillis(250);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> task = () -> {
            start.await();
            return dispatcher.dispatchOnce();
        };
        try {
            Future<Integer> first = pool.submit(task);
            Future<Integer> second = pool.submit(task);
            start.countDown();
            int total = first.get(30, TimeUnit.SECONDS) + second.get(30, TimeUnit.SECONDS);

            assertThat(total).isEqualTo(1);
            assertThat(sender.sentCount()).isEqualTo(1);
            assertThat(attemptsOf(row.getId())).isEqualTo(1);
            assertThat(statusOf(row.getId())).isEqualTo("SENT");
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------- lease y reintentos

    @Test
    void unClaimSinCompletarSeRecuperaTrasElLease() {
        User user = createUser("lease@dc.test", Role.DENTIST);
        Emission emission = staffInvitation(user, TestTime.now().plus(Duration.ofHours(1)));
        IdentityEmailOutbox row = outbox.enqueue(command(user, emission, "key-lease"));

        List<?> claimed = claims.claim(TestTime.now(), 10, Duration.ofMinutes(5));
        assertThat(claimed).hasSize(1);
        assertThat(attemptsOf(row.getId())).isEqualTo(1);
        assertThat(row(row.getId()).get("lease_until")).isNotNull();

        assertThat(dispatcher.dispatchOnce()).isZero(); // lease activo: no elegible

        TestTime.advance(Duration.ofMinutes(10));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(statusOf(row.getId())).isEqualTo("SENT");
        assertThat(attemptsOf(row.getId())).isEqualTo(2);
    }

    @Test
    void workerObsoletoNoComponeNiSobrescribeLaReservaNueva() {
        User user = createUser("stale-worker@dc.test", Role.DENTIST);
        Emission emission = staffInvitation(user, TestTime.now().plus(Duration.ofHours(1)));
        IdentityEmailOutbox row = outbox.enqueue(command(user, emission, "stale-worker"));
        ClaimedIdentityEmail old = claims.claim(TestTime.now(), 1, Duration.ofMinutes(5)).get(0);
        TestTime.advance(Duration.ofMinutes(5));
        assertThat(composer.compose(old)).isEmpty();
        claims.supersede(old, TestTime.now());
        assertThat(row(row.getId()).get("superseded_at")).isNull();
        ClaimedIdentityEmail current = claims.claim(TestTime.now(), 1, Duration.ofMinutes(5)).get(0);

        assertThat(composer.compose(old)).isEmpty();
        assertThatThrownBy(() -> claims.scheduleRetry(old, "old", TestTime.now(), TestTime.now()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> claims.markFailed(old, "old", TestTime.now()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> claims.markSent(old, TestTime.now()))
                .isInstanceOf(IllegalStateException.class);
        claims.supersede(old, TestTime.now());
        assertThat(row(row.getId()).get("superseded_at")).isNull();
        assertThat(composer.compose(current)).isPresent();
        claims.markSent(current, TestTime.now());
        assertThat(statusOf(row.getId())).isEqualTo("SENT");
        assertThat(attemptsOf(row.getId())).isEqualTo(2);
    }

    @Test
    void leaseAgotadoDuranteElLoteNoDescartaLasEntregasSinPreparar() {
        User firstUser = createUser("slow-batch-1@dc.test", Role.DENTIST);
        User secondUser = createUser("slow-batch-2@dc.test", Role.DENTIST);
        IdentityEmailOutbox first = outbox.enqueue(command(firstUser,
                staffInvitation(firstUser, TestTime.now().plus(Duration.ofHours(1))), "slow-batch-1"));
        IdentityEmailOutbox second = outbox.enqueue(command(secondUser,
                staffInvitation(secondUser, TestTime.now().plus(Duration.ofHours(1))), "slow-batch-2"));
        IdentityEmailDispatcher slow = new IdentityEmailDispatcher(claims, composer, properties, links,
                Optional.of(message -> {
                    sender.send(message);
                    TestTime.advance(properties.lease().plusSeconds(1));
                }), clock);

        assertThat(slow.dispatchOnce()).isEqualTo(1);
        assertThat(sender.sentCount()).isEqualTo(1);
        assertThat(row(first.getId()).get("superseded_at")).isNull();
        assertThat(row(second.getId()).get("superseded_at")).isNull();
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(sender.sentCount()).isEqualTo(2);
        assertThat(statusOf(first.getId())).isEqualTo("SENT");
        assertThat(statusOf(second.getId())).isEqualTo("SENT");
    }

    @Test
    void falloProgramaReintentoConBackoffYSeRecupera() {
        User user = createUser("retry@dc.test", Role.DENTIST);
        Emission emission = staffInvitation(user, TestTime.now().plus(Duration.ofDays(7)));
        IdentityEmailOutbox row = outbox.enqueue(command(user, emission, "key-retry"));
        sender.alwaysFail();

        assertThat(dispatcher.dispatchOnce()).isZero();
        Map<String, Object> failed = row(row.getId());
        assertThat(failed.get("status")).isEqualTo("PENDING");
        assertThat(((Number) failed.get("attempts")).intValue()).isEqualTo(1);
        assertThat(failed.get("last_error")).isEqualTo("EmailSendException");
        assertThat(failed.get("next_attempt_at")).isNotNull();
        assertThat(failed.get("lease_until")).isNull();
        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(attemptsOf(row.getId())).isEqualTo(1);

        TestTime.advance(Duration.ofHours(2));
        sender.succeed();
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        Map<String, Object> sent = row(row.getId());
        assertThat(sent.get("status")).isEqualTo("SENT");
        assertThat(((Number) sent.get("attempts")).intValue()).isEqualTo(2);
        assertThat(sent.get("last_error")).isNull();
    }

    @Test
    void agotaIntentosYPasaAFailedSinVolverAEnviar() {
        User user = createUser("failed@dc.test", Role.DENTIST);
        Emission emission = staffInvitation(user, TestTime.now().plus(Duration.ofDays(7)));
        IdentityEmailOutbox row = outbox.enqueue(command(user, emission, "key-failed"));
        sender.alwaysFail();

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(dispatcher.dispatchOnce()).isZero();
            TestTime.advance(Duration.ofHours(2));
        }

        Map<String, Object> failed = row(row.getId());
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(((Number) failed.get("attempts")).intValue()).isEqualTo(4);
        assertThat(failed.get("last_error")).isEqualTo("EmailSendException");

        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(((Number) row(row.getId()).get("attempts")).intValue()).isEqualTo(4);
        assertThat(sender.sentCount()).isZero();
    }

    // ----------------------------------------------------------- seguridad

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void excepcionDelEmisorNoFiltraTokenUrlNiSecreto(CapturedOutput output) {
        User user = createUser("leak@dc.test", Role.DENTIST);
        Emission emission = staffInvitation(user, TestTime.now().plus(Duration.ofHours(1)));
        IdentityEmailOutbox row = outbox.enqueue(command(user, emission, "leak-test"));
        IdentityEmailDispatcher failing = new IdentityEmailDispatcher(claims, composer, properties,
                links, Optional.of(message -> {
                    throw new IllegalStateException(message.toString() + links.linkSecret());
                }), clock);

        assertThat(failing.dispatchOnce()).isZero();
        assertThat(row(row.getId()).get("last_error")).isEqualTo("IllegalStateException");
        assertThat(output.getAll()).contains("IllegalStateException");
        assertThat(output.getAll()).doesNotContain(emission.token(), links.publicBaseUrl(), links.linkSecret());
        String dump = jdbc.queryForObject(
                "SELECT row_to_json(o)::text FROM identity_email_outbox o WHERE o.id = ?",
                String.class, row.getId());
        assertThat(dump).doesNotContain(emission.token(), links.publicBaseUrl(), links.linkSecret());
    }

    @Test
    void laBaseNoGuardaElTokenNiLaUrl() {
        User user = createUser("secret@dc.test", Role.DENTIST);
        Emission emission = staffInvitation(user, TestTime.now().plus(Duration.ofHours(1)));
        IdentityEmailOutbox row = outbox.enqueue(command(user, emission, "key-secret"));

        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);

        String dump = jdbc.queryForObject(
                "SELECT row_to_json(o)::text FROM identity_email_outbox o WHERE o.id = ?",
                String.class, row.getId());
        assertThat(dump)
                .as("ni el token en claro ni la URL completa se persisten")
                .doesNotContain(emission.token())
                .doesNotContain(links.publicBaseUrl())
                .doesNotContain("/activate?token=");
        assertThat(dump).contains(emission.hash());
        assertThat(row(row.getId()).get("last_error")).isNull();
    }

    // -------------------------------------------------------------- helpers

    private void revokeInvitations(Long userId) {
        jdbc.update("DELETE FROM invitations WHERE user_id = ?", userId);
    }

    private User createUser(String email, Role role) {
        return userRepository.saveAndFlush(User.create(email, "hash", role));
    }

    private Emission staffInvitation(User user, Instant expiresAt) {
        IdentityLinkTokens.IssuedLinkToken issued =
                tokens.issue(IdentityLinkTokens.Domain.STAFF_INVITATION, user.getId());
        String hash = tokens.hash(issued.token());
        invitationRepository.saveAndFlush(Invitation.issueForExistingUser(hash, user.getEmail(),
                Role.DENTIST, user.getId(), expiresAt));
        return new Emission(issued.token(), issued.nonce(), hash);
    }

    private Emission passwordReset(User user, Instant expiresAt) {
        IdentityLinkTokens.IssuedLinkToken issued =
                tokens.issue(IdentityLinkTokens.Domain.PASSWORD_RESET, user.getId());
        String hash = tokens.hash(issued.token());
        resetTokenRepository.saveAndFlush(PasswordResetToken.issue(user.getId(), hash, expiresAt));
        return new Emission(issued.token(), issued.nonce(), hash);
    }

    private Enqueue command(User user, Emission emission, String key) {
        return new Enqueue(user.getId(), IdentityEmailPurpose.STAFF_INVITATION, user.getEmail(),
                emission.nonce(), emission.hash(), key);
    }

    private long count() {
        return jdbc.queryForObject("SELECT count(*) FROM identity_email_outbox", Long.class);
    }

    private long activeCount() {
        return jdbc.queryForObject("SELECT count(*) FROM identity_email_outbox "
                + "WHERE status = 'PENDING' AND superseded_at IS NULL", Long.class);
    }

    private Map<String, Object> row(Long id) {
        return jdbc.queryForMap("SELECT * FROM identity_email_outbox WHERE id = ?", id);
    }

    private String statusOf(Long id) {
        return jdbc.queryForObject("SELECT status FROM identity_email_outbox WHERE id = ?",
                String.class, id);
    }

    private int attemptsOf(Long id) {
        return jdbc.queryForObject("SELECT attempts FROM identity_email_outbox WHERE id = ?",
                Integer.class, id);
    }

    private record Emission(String token, String nonce, String hash) {
    }
}

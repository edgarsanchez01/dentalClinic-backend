package com.dentalclinic.identity.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;

import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.application.IdentityEmailClaimService;
import com.dentalclinic.identity.application.IdentityEmailComposer;
import com.dentalclinic.identity.application.IdentityEmailDispatcher;
import com.dentalclinic.identity.application.StaffInvitationService.NewReceptionist;
import com.dentalclinic.identity.domain.*;
import com.dentalclinic.identity.domain.exception.*;
import com.dentalclinic.identity.infrastructure.config.IdentityEmailProperties;
import com.dentalclinic.identity.infrastructure.config.IdentityLinkProperties;

class IdentityLinkFlowsIntegrationTest extends IdentityLinkFlowTestSupport {
    @Autowired private IdentityEmailClaimService claims;
    @Autowired private IdentityEmailComposer composer;
    @Autowired private IdentityEmailProperties emailProperties;
    @Autowired private IdentityLinkProperties links;
    @Autowired private Clock clock;

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DENTIST", "RECEPTIONIST"})
    void altaHttpOutboxEmailYActivacionUnSoloUso(Role role) throws Exception {
        User owner = active("owner-flow@dc.test", Role.OWNER);
        String auth = sessions.login(owner.getEmail(), PASSWORD).accessToken();
        String endpoint = role == Role.DENTIST ? "/api/users/dentists" : "/api/users/receptionists";
        String body = role == Role.DENTIST
                ? "{\"email\":\"invited@dc.test\",\"firstName\":\"Ana\",\"lastName\":\"Perez\",\"phone\":\"123456789\"}"
                : "{\"email\":\"invited@dc.test\"}";
        String response = mvc.perform(post(endpoint).header("Authorization", "Bearer " + auth)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("token", "nonce", "passwordHash");
        User invited = users.findByEmail("invited@dc.test").orElseThrow();
        assertThat(invited.isActive()).isFalse();
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        String token = lastToken();
        assertThat(invitations.findByTokenHash(hasher.hash(token))).isPresent();
        mvc.perform(post("/api/auth/activate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"password\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(users.findById(invited.getId()).orElseThrow().isActive()).isTrue();
        assertThat(sessions.login(invited.getEmail(), NEW_PASSWORD).user().getRole()).isEqualTo(role);
        assertThatThrownBy(() -> activation.activate(token, PASSWORD)).isInstanceOf(InvalidTokenException.class);
        String dump = jdbc.queryForObject("SELECT row_to_json(o)::text FROM identity_email_outbox o", String.class);
        assertThat(dump).doesNotContain(token, "?token=", links.linkSecret());
    }

    @Test
    void reemisionSustituyePendienteYActivaSoloLaNueva() {
        var member = staff.inviteReceptionist(new NewReceptionist("reissue-flow@dc.test"));
        var old = outbox.findAll().get(0);
        String oldToken = tokens.token(com.dentalclinic.identity.infrastructure.token.IdentityLinkTokens.Domain.STAFF_INVITATION,
                member.id(), old.getTokenNonce());
        TestTime.advance(Duration.ofMinutes(1));
        var resent = staff.reissueInvitation(member.id());
        assertThat(resent.rawToken()).isNotEqualTo(oldToken);
        assertThat(outbox.findById(old.getId()).orElseThrow().getSupersededAt()).isNotNull();
        assertThatThrownBy(() -> activation.activate(oldToken, NEW_PASSWORD)).isInstanceOf(InvalidTokenException.class);
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        assertThat(lastToken()).isEqualTo(resent.rawToken());
        activation.activate(lastToken(), NEW_PASSWORD);
        assertThat(sender.sentCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void recuperacionHttpDeTodoRolCambiaPasswordYRevocaSesion(Role role) throws Exception {
        User user = active("recover@dc.test", role);
        var session = sessions.login(user.getEmail(), PASSWORD);
        forgot(user.getEmail(), "192.0.2." + (10 + role.ordinal()));
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        String token = lastToken();
        mvc.perform(post("/api/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(sessions.login(user.getEmail(), NEW_PASSWORD).user().getId()).isEqualTo(user.getId());
        assertThatThrownBy(() -> sessions.refresh(session.refreshToken())).isInstanceOf(NeutralAuthenticationException.class);
        assertThatThrownBy(() -> passwords.reset(token, PASSWORD)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void inexistenteInactivaCoalescidaYLimitadaDevuelvenElMismo202Vacio() throws Exception {
        User user = active("neutral@dc.test", Role.PATIENT);
        users.saveAndFlush(User.createPendingActivation("inactive@dc.test", "unused", Role.DENTIST));
        forgot("absent@dc.test", "192.0.2.40");
        forgot("inactive@dc.test", "192.0.2.40");
        forgot(user.getEmail(), "192.0.2.40");
        for (int i = 0; i < 5; i++) {
            forgot(user.getEmail(), "192.0.2.40");
        }
        assertThat(resets.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void cabecerasFalsasNoEludenElLimitePorIp() throws Exception {
        for (int i = 0; i < 4; i++) {
            active("ip-" + i + "@dc.test", Role.PATIENT);
            mvc.perform(post("/api/auth/password/forgot").with(request -> {
                        request.setRemoteAddr("192.0.2.50");
                        return request;
                    }).header("X-Forwarded-For", "198.51.100." + i)
                    .header("Forwarded", "for=198.51.100." + i)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"ip-" + i + "@dc.test\"}"))
                    .andExpect(status().isAccepted());
        }
        assertThat(outbox.count()).isEqualTo(3);
        TestTime.advance(Duration.ofMinutes(15));
        forgot("ip-3@dc.test", "192.0.2.50");
        assertThat(outbox.count()).isEqualTo(4);
    }

    @Test
    void solicitudesMaliciosasNoRevocanNiExtiendenUnResetVivo() {
        User user = active("coalesced@dc.test", Role.PATIENT);
        passwords.requestReset(user.getEmail());
        dispatcher.dispatchOnce();
        String raw = lastToken();
        var original = resets.findAll().get(0);
        for (int i = 0; i < 5; i++) {
            TestTime.advance(Duration.ofMinutes(2));
            passwords.requestReset(user.getEmail());
        }
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(resets.findById(original.getId()).orElseThrow().getExpiresAt()).isEqualTo(original.getExpiresAt());
        assertThat(sessions.login(user.getEmail(), PASSWORD).user().getId()).isEqualTo(user.getId());
        passwords.reset(raw, NEW_PASSWORD);
    }

    @Test
    void cooldownYLimiteDurableNoBloqueanConsumoYSeRecuperanSinVentanaDeslizante() {
        User user = active("limits@dc.test", Role.PATIENT);
        passwords.requestReset(user.getEmail());
        dispatcher.dispatchOnce();
        passwords.reset(lastToken(), PASSWORD);
        passwords.requestReset(user.getEmail());
        assertThat(outbox.count()).isEqualTo(1); // cooldown, sin prolongarlo
        TestTime.advance(Duration.ofMinutes(1));
        passwords.requestReset(user.getEmail());
        dispatcher.dispatchOnce();
        passwords.reset(lastToken(), PASSWORD);
        TestTime.advance(Duration.ofMinutes(1));
        passwords.requestReset(user.getEmail());
        assertThat(outbox.count()).isEqualTo(2); // máximo de 2 para este contexto
        TestTime.advance(Duration.ofHours(1));
        passwords.requestReset(user.getEmail());
        assertThat(outbox.count()).isEqualTo(3);
    }

    @Test
    void expiracionRevocaEmisionAnteriorYNoLaEntrega() {
        User user = active("expires@dc.test", Role.PATIENT);
        passwords.requestReset(user.getEmail());
        var old = resets.findAll().get(0);
        TestTime.advance(Duration.ofHours(1));
        passwords.requestReset(user.getEmail());
        assertThat(resets.findById(old.getId()).orElseThrow().getUsedAt()).isPresent();
        assertThat(outbox.findAll().stream().filter(row -> row.getSupersededAt() != null)).hasSize(1);
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
    }

    @Test
    void reemisionesRespetanCooldownYMaximoSinAlterarInvitacionVigente() {
        var member = staff.inviteReceptionist(new NewReceptionist("staff-limits@dc.test"));
        assertThatThrownBy(() -> staff.reissueInvitation(member.id())).isInstanceOf(ForbiddenOperationException.class);
        for (int i = 0; i < 2; i++) {
            TestTime.advance(Duration.ofMinutes(1));
            staff.reissueInvitation(member.id());
        }
        String hash = invitations.findAll().get(0).getTokenHash();
        TestTime.advance(Duration.ofMinutes(1));
        assertThatThrownBy(() -> staff.reissueInvitation(member.id())).isInstanceOf(ForbiddenOperationException.class);
        assertThat(invitations.findByTokenHash(hash)).isPresent();
        assertThat(outbox.count()).isEqualTo(3);
        TestTime.advance(Duration.ofHours(1));
        staff.reissueInvitation(member.id());
        assertThat(outbox.count()).isEqualTo(4);
    }

    @Test
    void resetsConcurrentesProducenUnaSolaEmision() throws Exception {
        User user = active("parallel-reset@dc.test", Role.PATIENT);
        parallel(() -> { passwords.requestReset(user.getEmail()); return true; },
                () -> { passwords.requestReset(user.getEmail()); return true; });
        assertThat(resets.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
    }

    @Test
    void reemisionesConcurrentesSoloUnaGana() throws Exception {
        var member = staff.inviteReceptionist(new NewReceptionist("parallel-staff@dc.test"));
        TestTime.advance(Duration.ofMinutes(1));
        Callable<Boolean> resend = () -> {
            try {
                staff.reissueInvitation(member.id());
                return true;
            } catch (ForbiddenOperationException expected) {
                return false;
            }
        };
        assertThat(parallel(resend, resend)).containsExactlyInAnyOrder(true, false);
        assertThat(invitations.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(2);
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
    }

    @Test
    void activacionYReemisionConcurrentesNoResucitanElTokenViejo() throws Exception {
        var member = staff.inviteReceptionist(new NewReceptionist("activation-race@dc.test"));
        dispatcher.dispatchOnce();
        String token = lastToken();
        TestTime.advance(Duration.ofMinutes(1));
        var outcomes = parallel(() -> {
            try { activation.activate(token, NEW_PASSWORD); return true; }
            catch (InvalidTokenException expected) { return false; }
        }, () -> {
            try { staff.reissueInvitation(member.id()); return true; }
            catch (ForbiddenOperationException expected) { return false; }
        });
        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertThatThrownBy(() -> activation.activate(token, PASSWORD)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void resetConcurrenteConsumeUnaSolaVez() throws Exception {
        User user = active("consume-race@dc.test", Role.PATIENT);
        passwords.requestReset(user.getEmail());
        dispatcher.dispatchOnce();
        String raw = lastToken();
        Callable<Boolean> consume = () -> {
            try { passwords.reset(raw, NEW_PASSWORD); return true; }
            catch (InvalidTokenException expected) { return false; }
        };
        assertThat(parallel(consume, consume)).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void falloDeOutboxRevierteAltaCompletaYPerfil() {
        rejectOutbox();
        try {
            assertThatThrownBy(() -> staff.inviteDentist(new com.dentalclinic.identity.application.StaffInvitationService.NewDentist(
                    "rollback@dc.test", "Ana", "Perez", "123", null, null, null, null)))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally { restoreOutbox(); }
        assertThat(users.count()).isZero();
        assertThat(invitations.count()).isZero();
        assertThat(outbox.count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dentist", Long.class)).isZero();
    }

    @Test
    void falloDeOutboxRevierteResetYRevocacionAnteriorCon202Neutro() throws Exception {
        User user = active("rollback-reset@dc.test", Role.PATIENT);
        var legacy = resets.saveAndFlush(PasswordResetToken.issue(user.getId(), hasher.hash("old"),
                TestTime.now().minusSeconds(1)));
        rejectOutbox();
        try { forgot(user.getEmail(), "192.0.2.60"); }
        finally { restoreOutbox(); }
        assertThat(resets.count()).isEqualTo(1);
        assertThat(resets.findById(legacy.getId()).orElseThrow().getUsedAt()).isEmpty();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void falloDeOutboxEnReemisionConservaInvitacionYEntregaAnteriores() {
        var member = staff.inviteReceptionist(new NewReceptionist("rollback-reissue@dc.test"));
        var old = outbox.findAll().get(0);
        TestTime.advance(Duration.ofMinutes(1));
        rejectOutbox();
        try {
            assertThatThrownBy(() -> staff.reissueInvitation(member.id()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally { restoreOutbox(); }
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(outbox.findById(old.getId()).orElseThrow().getSupersededAt()).isNull();
        assertThat(invitations.findByTokenHash(old.getTokenHash())).isPresent();
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        activation.activate(lastToken(), NEW_PASSWORD);
    }

    @Test
    void sinSmtpOutboxQuedaPendienteSinIntentos() {
        var user = active("no-smtp@dc.test", Role.PATIENT);
        passwords.requestReset(user.getEmail());
        var noSmtp = new IdentityEmailDispatcher(claims, composer, emailProperties, links, Optional.empty(), clock);
        assertThat(noSmtp.dispatchOnce()).isZero();
        assertThat(outbox.findAll().get(0).getAttempts()).isZero();
        assertThat(outbox.findAll().get(0).getStatus()).isEqualTo(IdentityEmailStatus.PENDING);
    }

    @Test
    void tokensLegacySeConsumenSinCambiarElContratoDelConsumidor() {
        User user = active("legacy-reset@dc.test", Role.PATIENT);
        String old = hasher.generateOpaqueToken();
        resets.saveAndFlush(PasswordResetToken.issue(user.getId(), hasher.hash(old), TestTime.now().plusSeconds(900)));
        assertThat(outbox.count()).isZero();
        passwords.reset(old, NEW_PASSWORD);
        User pending = users.saveAndFlush(User.createPendingActivation("legacy-staff@dc.test", "unused", Role.DENTIST));
        String invite = hasher.generateOpaqueToken();
        invitations.saveAndFlush(Invitation.issueForExistingUser(hasher.hash(invite), pending.getEmail(),
                pending.getRole(), pending.getId(), TestTime.now().plusSeconds(900)));
        activation.activate(invite, NEW_PASSWORD);
        assertThat(users.findById(pending.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void nuevaSolicitudMigraResetLegacySinTransporteYRevocaElAnterior() {
        User user = active("legacy-migrate@dc.test", Role.PATIENT);
        String old = hasher.generateOpaqueToken();
        resets.saveAndFlush(PasswordResetToken.issue(user.getId(), hasher.hash(old), TestTime.now().plusSeconds(900)));
        passwords.requestReset(user.getEmail());
        assertThat(outbox.count()).isEqualTo(1);
        assertThatThrownBy(() -> passwords.reset(old, NEW_PASSWORD)).isInstanceOf(InvalidTokenException.class);
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        passwords.reset(lastToken(), NEW_PASSWORD);
    }

    @Test
    void entregaFailedNoImpideNuevaSolicitudTrasCooldown() {
        User user = active("failed-reissue@dc.test", Role.PATIENT);
        passwords.requestReset(user.getEmail());
        sender.alwaysFail();
        for (int delay : List.of(1, 5, 25)) {
            dispatcher.dispatchOnce();
            TestTime.advance(Duration.ofMinutes(delay));
        }
        dispatcher.dispatchOnce();
        assertThat(outbox.findAll().get(0).getStatus()).isEqualTo(IdentityEmailStatus.FAILED);
        passwords.requestReset(user.getEmail());
        assertThat(outbox.count()).isEqualTo(2);
        sender.succeed();
        assertThat(dispatcher.dispatchOnce()).isEqualTo(1);
        passwords.reset(lastToken(), NEW_PASSWORD);
    }

    @Test
    void produccionNoExponeRutaDevYNoSeInvitaOwner() throws Exception {
        User owner = active("owner-route@dc.test", Role.OWNER);
        String auth = sessions.login(owner.getEmail(), PASSWORD).accessToken();
        mvc.perform(post("/api/users/{id}/invitations/resend", owner.getId())
                .header("Authorization", "Bearer " + auth)).andExpect(status().isNotFound());
        mvc.perform(post("/api/users/owners").header("Authorization", "Bearer " + auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"new-owner@dc.test\"}"))
                .andExpect(status().isForbidden());
    }

    private void forgot(String email, String ip) throws Exception {
        String result = mvc.perform(post("/api/auth/password/forgot").with(request -> {
                    request.setRemoteAddr(ip); return request;
                }).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertThat(result).isEmpty();
    }

    private void rejectOutbox() {
        jdbc.execute("ALTER TABLE identity_email_outbox ADD CONSTRAINT test_reject_delivery CHECK (false) NOT VALID");
    }

    private void restoreOutbox() {
        jdbc.execute("ALTER TABLE identity_email_outbox DROP CONSTRAINT test_reject_delivery");
    }

    private <T> List<T> parallel(Callable<T> first, Callable<T> second) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return first.call(); });
            var b = pool.submit(() -> { start.await(); return second.call(); });
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }
}

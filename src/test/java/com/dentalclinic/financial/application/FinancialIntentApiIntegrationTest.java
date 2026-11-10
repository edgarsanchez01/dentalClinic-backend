package com.dentalclinic.financial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import com.dentalclinic.financial.FinancialOperationsIntegrationSupport;
import com.dentalclinic.financial.application.command.CancelChargeCommand;
import com.dentalclinic.financial.application.command.ConfirmChargeCommand;
import com.dentalclinic.financial.application.command.RegisterPaymentCommand;
import com.dentalclinic.financial.application.command.ReversePaymentCommand;
import com.dentalclinic.financial.domain.FinancialIntentClosureUncertainException;
import com.dentalclinic.financial.domain.FinancialIntentOperation;
import com.dentalclinic.financial.domain.PaymentMethod;
import com.dentalclinic.financial.infrastructure.repository.FinancialIntentReadRepository;
import com.dentalclinic.identity.application.UserService;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.identity.infrastructure.security.JwtTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** B4: filtros JWT/rol reales, servicios B1-B3 reales y PostgreSQL 16 efimero V001-V021. */
class FinancialIntentApiIntegrationTest extends FinancialOperationsIntegrationSupport {
    private static final String BASE = "/api/financial-intents";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserService users;
    @Autowired private JwtTokenService jwt;
    @Autowired private Clock clock;
    @Autowired private FinancialIntentRegistrationService registration;
    @MockitoSpyBean private FinancialIntentReadRepository reads;
    @MockitoSpyBean private FinancialIntentExecutionService execution;
    @MockitoSpyBean private FinancialIdempotencyStore idempotency;

    @ParameterizedTest
    @EnumSource(FinancialIntentOperation.class)
    void registraConsultaEjecutaYReproduceSoloReferenciaSinFiltrarComando(FinancialIntentOperation operation) throws Exception {
        var c = valid(operation);
        var before = ledger();
        var created = write(BASE, c.body(), owner).andExpect(status().isCreated())
                .andExpect(header().string("Location", path(c))).andExpect(jsonPath("$.state").value("REGISTERED"));
        assertMinimal(json(created), false);
        assertThat(ledger()).isEqualTo(before);
        write(BASE, c.body(), owner).andExpect(status().isOk());
        read(path(c), owner).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("REGISTERED"));
        var confirmed = json(write(path(c) + "/execute", "{}", owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CONFIRMED")));
        assertMinimal(confirmed, true);
        assertThat(confirmed.path("effect").path("chargeId").longValue()).isPositive();
        if (operation == FinancialIntentOperation.FIN_REGISTER_PAYMENT || operation == FinancialIntentOperation.FIN_REVERSE_PAYMENT) {
            assertThat(confirmed.path("effect").path("movementId").longValue()).isPositive();
        }
        var committed = ledger();
        assertThat(json(write(path(c) + "/execute", "", owner).andExpect(status().isOk()))).isEqualTo(confirmed);
        assertThat(json(read(path(c), owner).andExpect(status().isOk()))).isEqualTo(confirmed);
        legacy(c).andExpect(operation == FinancialIntentOperation.FIN_CANCEL_CHARGE ? status().isOk() : status().isCreated());
        assertThat(ledger()).isEqualTo(committed);
        assertThat(receipts(c)).isEqualTo(1);
        read(BASE + "?view=pending", owner).andExpect(jsonPath("$.items").isEmpty());
        read(BASE + "?view=recent", owner).andExpect(jsonPath("$.items[0].state").value("CONFIRMED"));
        write(path(c) + "/abandon", "{}", owner).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INTENT_HAS_RECEIPT"));
    }

    @ParameterizedTest
    @EnumSource(FinancialIntentOperation.class)
    void abandonoIdempotenteBloqueaApiNuevaYEscritorAnterior(FinancialIntentOperation operation) throws Exception {
        var c = valid(operation);
        write(BASE, c.body(), owner).andExpect(status().isCreated());
        var before = ledger();
        var closed = json(write(path(c) + "/abandon", "", owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ABANDONED")));
        assertThat(json(write(path(c) + "/abandon", "{}", owner).andExpect(status().isOk()))).isEqualTo(closed);
        assertThat(json(write(BASE, c.body(), owner).andExpect(status().isOk()))).isEqualTo(closed);
        write(path(c) + "/execute", "", owner).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INTENT_CLOSED"));
        legacy(c).andExpect(status().isConflict());
        assertThat(ledger()).isEqualTo(before);
        assertThat(receipts(c)).isZero();
        read(BASE + "?view=pending", owner).andExpect(jsonPath("$.items").isEmpty());
        read(BASE + "?view=recent", owner).andExpect(jsonPath("$.items[0].state").value("ABANDONED"));
    }

    @ParameterizedTest
    @EnumSource(FinancialIntentOperation.class)
    void rechazoB3ConservaHttpTipificadoYConsultaRechazoSinNuevoEfecto(FinancialIntentOperation operation) throws Exception {
        Case c;
        String expected;
        if (operation == FinancialIntentOperation.FIN_CONFIRM_CHARGE) {
            var command = new ConfirmChargeCommand(patientId, null, List.of(), null, key());
            registration.register(command, owner); // B1 permite registrar comando antes de validar negocio.
            c = caseOf(operation, command.idempotencyKey(), command, null);
            expected = "CHARGE_ITEMS_REQUIRED";
        } else {
            c = valid(operation);
            if (operation == FinancialIntentOperation.FIN_REVERSE_PAYMENT) {
                var command = (ReversePaymentCommand) c.command();
                operations.reversePayment(new ReversePaymentCommand(c.chargeId(), command.movementId(), "correccion", key()), owner);
                expected = "PAYMENT_ALREADY_REVERSED";
            } else {
                operations.registerPayment(new RegisterPaymentCommand(c.chargeId(), PaymentMethod.CASH,
                        new BigDecimal("40"), null, key()), owner);
                expected = operation == FinancialIntentOperation.FIN_REGISTER_PAYMENT ? "PAYMENT_EXCEEDS_BALANCE" : "CHARGE_HAS_PAYMENTS";
            }
            write(BASE, c.body(), owner).andExpect(status().isCreated());
        }
        var before = ledger();
        write(path(c) + "/execute", "", owner)
                .andExpect(operation == FinancialIntentOperation.FIN_CONFIRM_CHARGE ? status().isBadRequest() : status().isConflict())
                .andExpect(jsonPath("$.code").value(expected)).andExpect(jsonPath("$.state").doesNotExist());
        read(path(c), owner).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("REJECTED"))
                .andExpect(jsonPath("$.rejection.code").value(expected)).andExpect(jsonPath("$.effect").doesNotExist());
        write(path(c) + "/execute", "", owner).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INTENT_CLOSED"));
        assertThat(ledger()).isEqualTo(before);
        assertThat(receipts(c)).isZero();
    }

    @Test
    void todosLosEndpointsExigenAutenticacionYNoStoreInclusoJwtInvalidoExpiradoOInactivo() throws Exception {
        var c = valid(FinancialIntentOperation.FIN_REGISTER_PAYMENT);
        for (String route : List.of(BASE + "?view=pending", path(c))) {
            mvc.perform(get(route)).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        }
        for (String route : List.of(BASE, path(c) + "/execute", path(c) + "/abandon")) {
            mvc.perform(post(route).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        }
        for (String bearer : List.of("invalido", jwt.issue(users.getById(owner.userId()), clock.instant().minusSeconds(3600)).value())) {
            mvc.perform(get(BASE + "?view=pending").header("Authorization", "Bearer " + bearer))
                    .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        }
        String bearer = token(owner);
        jdbc.update("UPDATE users SET active = FALSE WHERE id = ?", owner.userId());
        mvc.perform(get(BASE + "?view=pending").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void csrfExistenteSigueActivoConCookieYErrorNoStore() throws Exception {
        var c = valid(FinancialIntentOperation.FIN_CONFIRM_CHARGE);
        mvc.perform(post(BASE).header("Authorization", "Bearer " + token(owner))
                .cookie(new Cookie("dentalclinic_refresh", "sintetica"))
                .contentType(MediaType.APPLICATION_JSON).content(c.body())).andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(count("financial_intent")).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DENTIST", "PATIENT"})
    void rolesSinPermisoNoAccedenANingunaRuta(Role role) throws Exception {
        var actor = role == Role.DENTIST ? dentist : patientActor;
        var c = valid(FinancialIntentOperation.FIN_REGISTER_PAYMENT);
        write(BASE, c.body(), owner).andExpect(status().isCreated());
        read(BASE + "?view=pending", actor).andExpect(status().isForbidden());
        read(path(c), actor).andExpect(status().isForbidden());
        for (String route : List.of(BASE, path(c) + "/execute", path(c) + "/abandon")) {
            write(route, c.body(), actor).andExpect(status().isForbidden());
        }
    }

    @ParameterizedTest
    @EnumSource(FinancialIntentOperation.class)
    void uuidConocidoDeOtroActorNoPermiteConsultaEjecucionAbandonoNiDescubrimiento(FinancialIntentOperation operation) throws Exception {
        var c = valid(operation);
        write(BASE, c.body(), owner).andExpect(status().isCreated());
        // Segundo OWNER sintetico, exclusivamente en el PostgreSQL efimero de prueba.
        jdbc.update("UPDATE users SET role = 'OWNER' WHERE id = ?", otherDentist.userId());
        var other = new AuthenticatedUser(otherDentist.userId(), Role.OWNER);
        var before = allRows();
        var foreign = json(read(path(c), other).andExpect(status().isNotFound()));
        var absent = json(read(BASE + "/" + operation + "/" + key(), other).andExpect(status().isNotFound()));
        assertThat(foreign.path("message")).isEqualTo(absent.path("message"));
        for (String action : List.of("execute", "abandon")) {
            write(path(c) + "/" + action, "", other).andExpect(status().isNotFound());
        }
        read(BASE + "?view=pending", other).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        assertThat(allRows()).isEqualTo(before);
    }

    @Test
    void recepcionSoloGestionaAltaYPagoPropiosYRolPersistidoSeRevalida() throws Exception {
        for (var op : List.of(FinancialIntentOperation.FIN_CONFIRM_CHARGE, FinancialIntentOperation.FIN_REGISTER_PAYMENT)) {
            var c = valid(op);
            write(BASE, c.body(), reception).andExpect(status().isCreated());
            read(path(c), owner).andExpect(status().isNotFound());
            write(path(c) + "/execute", "", reception).andExpect(status().isOk());
        }
        for (var op : List.of(FinancialIntentOperation.FIN_REVERSE_PAYMENT, FinancialIntentOperation.FIN_CANCEL_CHARGE)) {
            write(BASE, valid(op).body(), reception).andExpect(status().isForbidden());
        }
        var correction = valid(FinancialIntentOperation.FIN_CANCEL_CHARGE);
        write(BASE, correction.body(), owner).andExpect(status().isCreated());
        String bearer = token(owner);
        jdbc.update("UPDATE users SET role = 'RECEPTIONIST' WHERE id = ?", owner.userId());
        mvc.perform(get(path(correction)).header("Authorization", "Bearer " + bearer)).andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(BASE + "?view=recent").header("Authorization", "Bearer " + bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "ACTOR", "KEY", "HASH", "COMMAND_KEY", "EXTRA_COMMAND", "DUPLICATE", "TRAILING", "FRACTION_ID", "ENUM_NUMBER", "STRING_AMOUNT", "STRING_ID", "NULL", "UPPER_UUID"})
    void formatoEstrictoNoPersisteNiFiltraValoresSensibles(String kind) throws Exception {
        var c = valid(FinancialIntentOperation.FIN_REGISTER_PAYMENT);
        ObjectNode body = (ObjectNode) mapper.readTree(c.body());
        ObjectNode command = (ObjectNode) body.get("command");
        switch (kind) {
            case "UNKNOWN" -> body.put("operation", "UNKNOWN");
            case "ACTOR" -> body.put("actorId", owner.userId());
            case "KEY" -> body.put("idempotencyKey", c.key());
            case "HASH" -> body.put("requestHash", "secret-hash");
            case "COMMAND_KEY" -> command.put("idempotencyKey", c.key());
            case "EXTRA_COMMAND" -> command.put("sensitive", "secret-notes");
            case "FRACTION_ID" -> command.put("chargeId", 1.5);
            case "ENUM_NUMBER" -> command.put("method", 0);
            case "STRING_AMOUNT" -> command.put("amount", "100");
            case "STRING_ID" -> command.put("chargeId", "1");
            case "NULL" -> body.putNull("command");
            case "UPPER_UUID" -> body.put("intentId", "AAAAAAAA-AAAA-4AAA-AAAA-AAAAAAAAAAAA");
            default -> { }
        }
        String raw = body.toString();
        if (kind.equals("DUPLICATE")) raw = raw.replace("\"command\":", "\"intentId\":\"secret-notes\",\"command\":");
        if (kind.equals("TRAILING")) raw += " {}";
        var before = allRows();
        var response = write(BASE, raw, owner).andExpect(status().isBadRequest()).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("secret-hash", "secret-notes", "requestHash");
        assertThat(allRows()).isEqualTo(before);
    }

    @Test
    void conflictoDePayloadNoConsumeOtraIdentidadYNoCierra() throws Exception {
        var c = valid(FinancialIntentOperation.FIN_REGISTER_PAYMENT);
        write(BASE, c.body(), owner).andExpect(status().isCreated());
        ObjectNode changed = (ObjectNode) mapper.readTree(c.body());
        ((ObjectNode) changed.get("command")).put("amount", 99);
        write(BASE, changed.toString(), owner).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INTENT_COMMAND_CONFLICT"));
        read(path(c), owner).andExpect(jsonPath("$.state").value("REGISTERED"));
        assertThat(receipts(c)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?view=all", "?view=pending&size=0", "?view=recent&size=51", "?view=pending&size=no",
            "?view=pending&cursor=invalid", "?view=pending&cursor=", "?view=pending&actorId=1", "?view=pending&view=recent"})
    void descubrimientoRechazaFiltrosYCursoresInvalidos(String query) throws Exception {
        read(BASE + query, owner).andExpect(status().isBadRequest());
    }

    @Test
    void accionesRechazanPayloadAlternativoYHeadersDeClaveNoCambianIdentidad() throws Exception {
        var c = valid(FinancialIntentOperation.FIN_REGISTER_PAYMENT);
        mvc.perform(post(BASE).header("Authorization", "Bearer " + token(owner)).header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON).content(c.body())).andExpect(status().isBadRequest());
        write(BASE, c.body(), owner).andExpect(status().isCreated());
        for (String action : List.of("execute", "abandon")) {
            for (String body : List.of("{\"amount\":1}", "{\"actorId\":1}", "null", "[]", "{} {}")) {
                write(path(c) + "/" + action, body, owner).andExpect(status().isBadRequest());
            }
        }
        assertThat(receipts(c)).isZero();
    }

    @Test
    void formatoDeRutaYContentTypeInvalidosNoSePublicanComoResultadoIncierto() throws Exception {
        var c = valid(FinancialIntentOperation.FIN_REGISTER_PAYMENT);
        mvc.perform(post(BASE).header("Authorization", "Bearer " + token(owner)).contentType(MediaType.TEXT_PLAIN).content(c.body()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INTENT_REQUEST_INVALID"))
                .andExpect(header().string("Cache-Control", "no-store"));
        read(BASE + "/FIN_REGISTER_PAYMENT/not-a-uuid", owner).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INTENT_REQUEST_INVALID"));
        read(BASE + "/UNKNOWN/" + c.key(), owner).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INTENT_REQUEST_INVALID"));
        assertThat(count("financial_intent")).isZero();
    }

    @Test
    void cursorEsEstableConEmpatesUuidRepetidoYAltasConcurrentesSinMezclarActores() throws Exception {
        List<Case> cases = new ArrayList<>();
        String shared = key();
        cases.add(caseOf(FinancialIntentOperation.FIN_CONFIRM_CHARGE, shared,
                command(null, null, "100", shared), null));
        var charge = confirmed("100");
        cases.add(caseOf(FinancialIntentOperation.FIN_REGISTER_PAYMENT, shared,
                new RegisterPaymentCommand(charge.getId(), PaymentMethod.CASH, new BigDecimal("10"), null, shared), charge.getId()));
        for (int i = 0; i < 5; i++) cases.add(valid(FinancialIntentOperation.FIN_CONFIRM_CHARGE));
        for (var c : cases) write(BASE, c.body(), owner).andExpect(status().isCreated());
        jdbc.update("UPDATE financial_intent SET registered_at = '2026-10-25T00:00:00Z'");
        var foreign = valid(FinancialIntentOperation.FIN_CONFIRM_CHARGE);
        write(BASE, foreign.body(), reception).andExpect(status().isCreated());
        var page = json(read(BASE + "?view=pending&size=2", owner).andExpect(status().isOk()));
        String firstCursor = page.path("nextCursor").textValue();
        read(BASE + "?view=recent&cursor=" + firstCursor, owner).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INTENT_CURSOR_INVALID"));
        var newCase = valid(FinancialIntentOperation.FIN_CONFIRM_CHARGE);
        write(BASE, newCase.body(), owner).andExpect(status().isCreated());
        var seen = new HashSet<String>();
        int total = 0;
        while (true) {
            for (JsonNode item : page.path("items")) {
                assertThat(seen.add(item.path("intentId").textValue() + "/" + item.path("operation").textValue())).isTrue();
                assertMinimal(item, false);
                assertThat(item.path("intentId").textValue()).isNotEqualTo(foreign.key()).isNotEqualTo(newCase.key());
                total++;
            }
            if (!page.has("nextCursor")) break;
            assertThat(total).isLessThan(20);
            page = json(read(BASE + "?view=pending&size=2&cursor=" + page.path("nextCursor").textValue(), owner).andExpect(status().isOk()));
        }
        assertThat(total).isEqualTo(cases.size());
    }

    @Test
    void limite50YDefault20SeAplicanEnSqlSinConteosGlobales() throws Exception {
        for (int i = 0; i < 51; i++) registration.register(command(null, null, "100", key()), owner);
        read(BASE + "?view=pending", owner).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.nextCursor").isString()).andExpect(jsonPath("$.totalElements").doesNotExist());
        var first = json(read(BASE + "?view=recent&size=50", owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(50)));
        read(BASE + "?view=recent&size=50&cursor=" + first.path("nextCursor").textValue(), owner)
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void recentIncluyeLosCuatroEstadosYPendingSoloRegistered() throws Exception {
        var pending = valid(FinancialIntentOperation.FIN_CONFIRM_CHARGE);
        var confirmed = valid(FinancialIntentOperation.FIN_CONFIRM_CHARGE);
        var abandoned = valid(FinancialIntentOperation.FIN_CONFIRM_CHARGE);
        var rejected = valid(FinancialIntentOperation.FIN_REGISTER_PAYMENT);
        for (var c : List.of(pending, confirmed, abandoned, rejected)) write(BASE, c.body(), owner).andExpect(status().isCreated());
        write(path(confirmed) + "/execute", "", owner).andExpect(status().isOk());
        write(path(abandoned) + "/abandon", "", owner).andExpect(status().isOk());
        operations.registerPayment(new RegisterPaymentCommand(rejected.chargeId(), PaymentMethod.CASH, new BigDecimal("40"), null, key()), owner);
        write(path(rejected) + "/execute", "", owner).andExpect(status().isConflict());
        var recent = json(read(BASE + "?view=recent", owner).andExpect(status().isOk()));
        var states = new HashSet<String>();
        recent.path("items").forEach(item -> states.add(item.path("state").textValue()));
        assertThat(states).containsExactlyInAnyOrder("REGISTERED", "CONFIRMED", "REJECTED", "ABANDONED");
        read(BASE + "?view=pending", owner).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].intentId").value(pending.key()));
    }

    @Test
    void confirmadoSigueDerivadoDelReciboAunqueElCargoSeaAnuladoDespues() throws Exception {
        var c = valid(FinancialIntentOperation.FIN_CONFIRM_CHARGE);
        write(BASE, c.body(), owner).andExpect(status().isCreated());
        var original = json(write(path(c) + "/execute", "", owner).andExpect(status().isOk()));
        long charge = original.path("effect").path("chargeId").longValue();
        operations.cancelCharge(new CancelChargeCommand(charge, "secret-notes posterior", key()), owner);
        assertThat(json(read(path(c), owner).andExpect(status().isOk()))).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HASH", "STATUS", "VERSION", "VERSION_OVERFLOW", "EMPTY", "ACTOR", "TYPE", "AMOUNT", "MISSING_EFFECT", "CLOSED"})
    void reciboIncompatibleNuncaSeConvierteEnEstadoSupuestoNiPaginaParcial(String kind) throws Exception {
        var c = valid(FinancialIntentOperation.FIN_REGISTER_PAYMENT);
        write(BASE, c.body(), owner).andExpect(status().isCreated());
        write(path(c) + "/execute", "", owner).andExpect(status().isOk());
        switch (kind) {
            case "HASH" -> jdbc.update("UPDATE http_idempotency SET request_hash = ? WHERE idempotency_key = ?", "a".repeat(64), c.key());
            case "STATUS" -> jdbc.update("UPDATE http_idempotency SET response_status = 400 WHERE idempotency_key = ?", c.key());
            case "VERSION" -> mutateReceipt(c, "{snapshotVersion}", "99");
            case "VERSION_OVERFLOW" -> mutateReceipt(c, "{snapshotVersion}", "4294967297");
            case "EMPTY" -> jdbc.update("UPDATE http_idempotency SET response_body = '{}'::jsonb WHERE idempotency_key = ?", c.key());
            case "ACTOR" -> mutateReceipt(c, "{result,performedByUserId}", reception.userId().toString());
            case "TYPE" -> mutateReceipt(c, "{result,type}", "\"REVERSAL\"");
            case "AMOUNT" -> mutateReceipt(c, "{result,amount}", "\"secret-notes\"");
            case "MISSING_EFFECT" -> mutateReceipt(c, "{result,id}", "9223372036854775807");
            case "CLOSED" -> jdbc.update("UPDATE financial_intent SET disposition = 'ABANDONED', closed_at = clock_timestamp() WHERE idempotency_key = ?", c.key());
        }
        var before = allRows();
        for (String route : List.of(path(c), BASE + "?view=recent")) {
            read(route, owner).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INTENT_RECEIPT_INCOMPATIBLE"))
                    .andExpect(jsonPath("$.state").doesNotExist()).andExpect(jsonPath("$.items").doesNotExist());
        }
        write(path(c) + "/execute", "", owner).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INTENT_RECEIPT_INCOMPATIBLE"));
        assertThat(allRows()).isEqualTo(before);
    }

    @Test
    void b1DecimalPerdidoEsConsultableSinRechazarNiInventarComando() throws Exception {
        var charge = confirmed("100");
        var command = new RegisterPaymentCommand(charge.getId(), PaymentMethod.CASH, new BigDecimal("1E+2"), null, key());
        var c = caseOf(FinancialIntentOperation.FIN_REGISTER_PAYMENT, command.idempotencyKey(), command, charge.getId());
        registration.register(command, owner);
        jdbc.update("UPDATE financial_intent SET command_version = 1, command_body = CAST(? AS jsonb) WHERE idempotency_key = ?",
                mapper.writeValueAsString(command), c.key());
        read(path(c), owner).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("REGISTERED"));
        write(path(c) + "/execute", "", owner).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INTENT_COMMAND_UNAVAILABLE"));
        read(path(c), owner).andExpect(jsonPath("$.state").value("REGISTERED"));
        write(path(c) + "/abandon", "", owner).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ABANDONED"));
    }

    @Test
    void b1DecimalPerdidoConReciboConservaConsultaYReplaySinReescribirlo() throws Exception {
        var charge = confirmed("100");
        var command = new RegisterPaymentCommand(charge.getId(), PaymentMethod.CASH, new BigDecimal("1E+2"), null, key());
        var c = caseOf(FinancialIntentOperation.FIN_REGISTER_PAYMENT, command.idempotencyKey(), command, charge.getId());
        registration.register(command, owner);
        operations.registerPayment(command, owner);
        jdbc.update("UPDATE financial_intent SET command_version = 1, command_body = CAST(? AS jsonb) WHERE idempotency_key = ?",
                mapper.writeValueAsString(command), c.key());
        var before = allRows();
        read(path(c), owner).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CONFIRMED"));
        write(path(c) + "/execute", "", owner).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CONFIRMED"));
        assertThat(allRows()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1E+2", "0.10", "100.00", "1.00E+2"})
    void codigoDecimalHttpPreservaHashHistoricoSinNormalizarExponente(String amount) throws Exception {
        var charge = confirmed("100");
        String key = key();
        String body = "{\"operation\":\"FIN_REGISTER_PAYMENT\",\"intentId\":\"" + key + "\",\"command\":{\"chargeId\":"
                + charge.getId() + ",\"method\":\"CASH\",\"amount\":" + amount + "}}";
        write(BASE, body, owner).andExpect(status().isCreated());
        var command = new RegisterPaymentCommand(charge.getId(), PaymentMethod.CASH, new BigDecimal(amount), null, key);
        String hash = new TransactionTemplate(transactions).execute(status -> idempotency.requestHash(command));
        assertThat(jdbc.queryForObject("SELECT request_hash FROM financial_intent WHERE idempotency_key = ?", String.class, key))
                .isEqualTo(hash);
    }

    @Test
    void getSoloLeeSinCrearRecibosNiActualizarDisposiciones() throws Exception {
        var c = valid(FinancialIntentOperation.FIN_CONFIRM_CHARGE);
        write(BASE, c.body(), owner).andExpect(status().isCreated());
        var before = allRows();
        for (int i = 0; i < 3; i++) {
            read(path(c), owner).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("REGISTERED"));
            read(BASE + "?view=pending", owner).andExpect(status().isOk());
            read(BASE + "?view=recent", owner).andExpect(status().isOk());
        }
        assertThat(allRows()).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(FinancialIntentOperation.class)
    void registrosConcurrentesDistinguen201De200SinTimestampHeuristico(FinancialIntentOperation operation) throws Exception {
        var c = valid(operation);
        var results = race(() -> write(BASE, c.body(), owner).andReturn().getResponse(),
                () -> write(BASE, c.body(), owner).andReturn().getResponse());
        assertThat(results.stream().map(MockHttpServletResponse::getStatus)).containsExactlyInAnyOrder(201, 200);
        assertThat(count("financial_intent")).isEqualTo(1);
        assertThat(receipts(c)).isZero();
    }

    @Test
    void registroConcurrenteConPayloadDistintoDevuelveConflictoSinReemplazarComando() throws Exception {
        var c = valid(FinancialIntentOperation.FIN_REGISTER_PAYMENT);
        ObjectNode changed = (ObjectNode) mapper.readTree(c.body());
        ((ObjectNode) changed.get("command")).put("amount", 99);
        var results = race(() -> write(BASE, c.body(), owner).andReturn().getResponse(),
                () -> write(BASE, changed.toString(), owner).andReturn().getResponse());
        assertThat(results.stream().map(MockHttpServletResponse::getStatus)).containsExactlyInAnyOrder(201, 409);
        assertThat(count("financial_intent")).isEqualTo(1);
        assertThat(receipts(c)).isZero();
    }

    @ParameterizedTest
    @EnumSource(FinancialIntentOperation.class)
    void ejecucionesConcurrentesSoloConfirmanUnEfecto(FinancialIntentOperation operation) throws Exception {
        var c = valid(operation);
        write(BASE, c.body(), owner).andExpect(status().isCreated());
        var results = race(() -> write(path(c) + "/execute", "", owner).andReturn().getResponse(),
                () -> write(path(c) + "/execute", "", owner).andReturn().getResponse());
        assertThat(results.stream().map(MockHttpServletResponse::getStatus)).containsOnly(200);
        assertThat(mapper.readTree(results.get(0).getContentAsString())).isEqualTo(mapper.readTree(results.get(1).getContentAsString()));
        assertThat(receipts(c)).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(FinancialIntentOperation.class)
    void carreraAbandonoEjecucionNoProduceEfectosTrasCierre(FinancialIntentOperation operation) throws Exception {
        var c = valid(operation);
        write(BASE, c.body(), owner).andExpect(status().isCreated());
        var before = ledger();
        var results = race(() -> write(path(c) + "/execute", "", owner).andReturn().getResponse(),
                () -> write(path(c) + "/abandon", "", owner).andReturn().getResponse());
        if (receipts(c) == 1) {
            assertThat(results.get(0).getStatus()).isEqualTo(200);
            assertThat(results.get(1).getStatus()).isEqualTo(409);
            read(path(c), owner).andExpect(jsonPath("$.state").value("CONFIRMED"));
        } else {
            assertThat(results.get(0).getStatus()).isEqualTo(409);
            assertThat(results.get(1).getStatus()).isEqualTo(200);
            read(path(c), owner).andExpect(jsonPath("$.state").value("ABANDONED"));
            assertThat(ledger()).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @EnumSource(FinancialIntentOperation.class)
    void consultaDuranteFlushNoConfundeEfectoSinCommitConConfirmado(FinancialIntentOperation operation) throws Exception {
        var c = valid(operation);
        write(BASE, c.body(), owner).andExpect(status().isCreated());
        CountDownLatch flushed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FinancialIdempotencyStore target = AopTestUtils.getUltimateTargetObject(idempotency);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            flushed.countDown();
            await(release);
            return result;
        }).when(target).remember(eq(owner), eq(operation.name()), eq(c.key()), any(), anyInt(), any(), any());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var pending = pool.submit(() -> write(path(c) + "/execute", "", owner).andReturn().getResponse());
            try {
                assertThat(flushed.await(10, TimeUnit.SECONDS)).isTrue();
                read(path(c), owner).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("REGISTERED"))
                        .andExpect(jsonPath("$.effect").doesNotExist());
                read(BASE + "?view=pending", owner).andExpect(jsonPath("$.items[0].state").value("REGISTERED"));
            } finally { release.countDown(); }
            assertThat(pending.get(15, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
        }
        read(path(c), owner).andExpect(jsonPath("$.state").value("CONFIRMED"));
    }

    @ParameterizedTest
    @EnumSource(FinancialIntentOperation.class)
    void errorTecnicoDespuesDelFlushNoPublicaRechazoNiDejaEfectos(FinancialIntentOperation operation) throws Exception {
        var c = valid(operation);
        write(BASE, c.body(), owner).andExpect(status().isCreated());
        var before = allRows();
        FinancialIdempotencyStore target = AopTestUtils.getUltimateTargetObject(idempotency);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataAccessResourceFailureException("secret-notes: fallo tecnico");
        }).when(target).remember(eq(owner), eq(operation.name()), eq(c.key()), any(), anyInt(), any(), any());
        write(path(c) + "/execute", "", owner).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INTENT_RESULT_UNCERTAIN")).andExpect(jsonPath("$.state").doesNotExist());
        assertThat(allRows()).isEqualTo(before);
        assertThat(receipts(c)).isZero();
        read(path(c), owner).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("REGISTERED"));
        reset(target);
        write(path(c) + "/execute", "", owner).andExpect(status().isOk());
        assertThat(receipts(c)).isEqualTo(1);
    }

    @Test
    void perdidaDeConsultaTrasCommitDevuelveIncertidumbreSinDuplicarYSeRecuperaPorGet() throws Exception {
        var c = valid(FinancialIntentOperation.FIN_REGISTER_PAYMENT);
        write(BASE, c.body(), owner).andExpect(status().isCreated());
        FinancialIntentReadRepository target = AopTestUtils.getUltimateTargetObject(reads);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) return invocation.callRealMethod(); // Preflight de solo lectura.
            throw new DataAccessResourceFailureException("secret-notes: conexion perdida");
        }).when(target).find(eq(owner.userId()), eq(c.operation()), eq(c.key()));
        var response = write(path(c) + "/execute", "", owner).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INTENT_RESULT_UNCERTAIN")).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("secret-notes", "REGISTERED", "REJECTED");
        assertThat(receipts(c)).isEqualTo(1);
        read(path(c), owner).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("INTENT_VERIFICATION_UNAVAILABLE"));
        reset(target);
        read(path(c), owner).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CONFIRMED"));
        write(path(c) + "/execute", "", owner).andExpect(status().isOk());
        assertThat(receipts(c)).isEqualTo(1);
    }

    @Test
    void incertidumbreB3NoSeTraduceARechazoNiRegistroDefinitivo() throws Exception {
        var c = valid(FinancialIntentOperation.FIN_REGISTER_PAYMENT);
        write(BASE, c.body(), owner).andExpect(status().isCreated());
        FinancialIntentExecutionService target = AopTestUtils.getUltimateTargetObject(execution);
        doThrow(new FinancialIntentClosureUncertainException()).when(target).registerPayment(c.key(), owner);
        write(path(c) + "/execute", "", owner).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INTENT_CLOSURE_UNCERTAIN")).andExpect(jsonPath("$.state").doesNotExist());
        assertThat(receipts(c)).isZero();
    }

    private Case valid(FinancialIntentOperation operation) throws Exception {
        String key = key();
        if (operation == FinancialIntentOperation.FIN_CONFIRM_CHARGE) {
            return caseOf(operation, key, command(null, null, "100", key), null);
        }
        var charge = confirmed("100");
        Object command = switch (operation) {
            case FIN_REGISTER_PAYMENT -> new RegisterPaymentCommand(charge.getId(), PaymentMethod.CASH, new BigDecimal("100"), "secret-notes", key);
            case FIN_REVERSE_PAYMENT -> new ReversePaymentCommand(charge.getId(), payment(charge, "100", key()).getId(), "secret-notes", key);
            case FIN_CANCEL_CHARGE -> new CancelChargeCommand(charge.getId(), "secret-notes", key);
            default -> throw new IllegalArgumentException();
        };
        return caseOf(operation, key, command, charge.getId());
    }

    private Case caseOf(FinancialIntentOperation operation, String key, Object command, Long charge) throws Exception {
        ObjectNode commandBody = mapper.valueToTree(command);
        commandBody.remove("idempotencyKey");
        ObjectNode body = mapper.createObjectNode().put("operation", operation.name()).put("intentId", key);
        body.set("command", commandBody);
        return new Case(operation, key, command, body.toString(), charge);
    }

    private String token(AuthenticatedUser actor) { return jwt.issue(users.getById(actor.userId()), clock.instant()).value(); }
    private static String path(Case c) { return BASE + "/" + c.operation() + "/" + c.key(); }

    private ResultActions write(String route, String body, AuthenticatedUser actor) throws Exception {
        return mvc.perform(post(route).header("Authorization", "Bearer " + token(actor)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    private ResultActions read(String route, AuthenticatedUser actor) throws Exception {
        return mvc.perform(get(route).header("Authorization", "Bearer " + token(actor)))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    private JsonNode json(ResultActions result) throws Exception { return mapper.readTree(result.andReturn().getResponse().getContentAsString()); }

    private void assertMinimal(JsonNode value, boolean effect) {
        var fields = new HashSet<String>();
        value.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).isSubsetOf("operation", "intentId", "state", "registeredAt", "closedAt", "rejectedAttemptAt", "rejection", "effect");
        assertThat(value.toString()).doesNotContain("secret-notes", "command", "hash", "actorUserId", "snapshot", "notes", "amount");
        assertThat(value.has("effect")).isEqualTo(effect);
    }

    private ResultActions legacy(Case c) throws Exception {
        // No convertir 1E+2 a 100.0 al fabricar el replay legacy: seria OTRO hash.
        ObjectNode command = (ObjectNode) mapper.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true)).readTree(c.body()).get("command");
        String route = switch (c.operation()) {
            case FIN_CONFIRM_CHARGE -> "/api/charges";
            case FIN_REGISTER_PAYMENT -> "/api/charges/" + command.remove("chargeId").asLong() + "/payments";
            case FIN_REVERSE_PAYMENT -> "/api/charges/" + command.remove("chargeId").asLong() + "/movements/"
                    + command.remove("movementId").asLong() + "/reverse";
            case FIN_CANCEL_CHARGE -> "/api/charges/" + command.remove("chargeId").asLong() + "/cancel";
        };
        return mvc.perform(post(route).header("Authorization", "Bearer " + token(owner)).header("Idempotency-Key", c.key())
                .contentType(MediaType.APPLICATION_JSON).content(command.toString()));
    }

    private long receipts(Case c) {
        return jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE actor_user_id = ? AND operation = ? AND idempotency_key = ?",
                Long.class, owner.userId(), c.operation().name(), c.key());
    }

    private void mutateReceipt(Case c, String jsonPath, String json) {
        jdbc.update("UPDATE http_idempotency SET response_body = jsonb_set(response_body, CAST(? AS text[]), CAST(? AS jsonb)) WHERE idempotency_key = ?",
                jsonPath, json, c.key());
    }

    private List<List<String>> ledger() {
        return Stream.of("charge", "charge_item", "financial_movement", "http_idempotency")
                .map(t -> jdbc.queryForList("SELECT to_jsonb(t)::text FROM " + t + " t ORDER BY to_jsonb(t)::text", String.class)).toList();
    }

    private List<List<String>> allRows() {
        var rows = new ArrayList<>(ledger());
        rows.add(jdbc.queryForList("SELECT to_jsonb(t)::text FROM financial_intent t ORDER BY to_jsonb(t)::text", String.class));
        return rows;
    }

    private static List<MockHttpServletResponse> race(Callable<MockHttpServletResponse> a, Callable<MockHttpServletResponse> b) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of(a, b).stream().map(call -> pool.submit(() -> {
                ready.countDown();
                await(start);
                return call.call();
            })).toList();
            try { assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { start.countDown(); }
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("timeout de prueba"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
    }

    private record Case(FinancialIntentOperation operation, String key, Object command, String body, Long chargeId) { }
}

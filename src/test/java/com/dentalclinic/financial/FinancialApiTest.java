package com.dentalclinic.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.dentalclinic.financial.application.command.ConfirmChargeCommand;
import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.identity.application.UserService;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.identity.infrastructure.security.JwtTokenService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** MockMvc, filtros JWT/rol reales, servicios reales y PostgreSQL 16 efimero con V001..V016. */
class FinancialApiTest extends FinancialOperationsIntegrationSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserService users;
    @Autowired private JwtTokenService jwt;
    @Autowired private Clock clock;

    private String token(AuthenticatedUser actor) {
        return jwt.issue(users.getById(actor.userId()), clock.instant()).value();
    }

    private ObjectNode chargeBody(Long appointmentId, Long treatingDentistId, String amount) {
        ObjectNode body = mapper.createObjectNode().put("patientId", patientId);
        if (appointmentId != null) body.put("appointmentId", appointmentId);
        ObjectNode item = body.putArray("items").addObject().put("description", "Concepto historico")
                .put("quantity", 1).put("unitPrice", new BigDecimal(amount));
        if (treatingDentistId != null) item.put("treatingDentistId", treatingDentistId);
        return body;
    }

    private ResultActions write(String path, String body, AuthenticatedUser actor, String key) throws Exception {
        var request = post(path).header("Authorization", "Bearer " + token(actor))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request);
    }

    private ResultActions read(String path, AuthenticatedUser actor) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + token(actor)));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readTree(result.andReturn().getResponse().getContentAsString());
    }

    private Long create(ObjectNode body, AuthenticatedUser actor) throws Exception {
        return json(write("/api/charges", body.toString(), actor, key()).andExpect(status().isCreated()))
                .get("id").longValue();
    }

    private JsonNode pay(Long chargeId, String amount, AuthenticatedUser actor, String key) throws Exception {
        return json(write("/api/charges/" + chargeId + "/payments",
                "{\"method\":\"CASH\",\"amount\":" + amount + "}", actor, key).andExpect(status().isCreated()));
    }

    private String reversePath(Long chargeId, long movementId) {
        return "/api/charges/" + chargeId + "/movements/" + movementId + "/reverse";
    }

    @Test
    void ownerCreaConsultaPagaReversaYAnulaConEstadosDerivados() throws Exception {
        ObjectNode body = chargeBody(ownAppointmentId, dentistId, "100.30");
        Long id = create(body, owner);
        read("/api/charges/" + id, owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDIENTE"))
                .andExpect(jsonPath("$.items[0].description").value("Concepto historico"))
                .andExpect(jsonPath("$.movements").isEmpty());
        long first = pay(id, "0.10", owner, key()).get("id").longValue();
        read("/api/charges/" + id, owner).andExpect(jsonPath("$.status").value("PARCIAL"))
                .andExpect(jsonPath("$.pendingBalance").value(100.20));
        long second = pay(id, "100.20", owner, key()).get("id").longValue();
        read("/api/charges/" + id, owner).andExpect(jsonPath("$.status").value("PAGADO"))
                .andExpect(jsonPath("$.pendingBalance").value(0));
        write(reversePath(id, first), "{\"reason\":\"Error A\"}", owner, key()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("REVERSAL"))
                .andExpect(jsonPath("$.reversesMovementId").value(first)).andExpect(jsonPath("$.amount").value(0.10))
                .andExpect(jsonPath("$.paymentMethod").doesNotExist());
        write(reversePath(id, second), "{\"reason\":\"Error B\"}", owner, key()).andExpect(status().isCreated());
        read("/api/charges?status=PENDIENTE", owner).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].appliedAmount").value(0));
        write("/api/charges/" + id + "/cancel", "{\"reason\":\"Anulacion explicita\"}", owner, key())
                .andExpect(status().isOk()).andExpect(jsonPath("$.cancelledByUserId").value(owner.userId()))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());
        read("/api/charges/" + id, owner).andExpect(jsonPath("$.status").value("ANULADO"))
                .andExpect(jsonPath("$.pendingBalance").value(0)).andExpect(jsonPath("$.movements.length()").value(4));
        read("/api/charges?status=ANULADO", owner).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].pendingBalance").value(0));
        read("/api/charges/" + id + "/items", owner).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        read("/api/charges/" + id + "/movements", owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
        assertThat(jdbc.queryForObject("SELECT status FROM appointment WHERE id = ?", String.class, ownAppointmentId))
                .isEqualTo("COMPLETED");
    }

    @Test
    void receptionGestionaGlobalmentePeroNoReversaNiAnula() throws Exception {
        Long id = create(chargeBody(null, null, "100"), reception);
        JsonNode payment = pay(id, "20", reception, key());
        read("/api/charges/" + id, reception).andExpect(status().isOk());
        read("/api/charges", reception).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        write(reversePath(id, payment.get("id").longValue()), "{\"reason\":\"Error\"}", reception, key())
                .andExpect(status().isForbidden());
        write("/api/charges/" + id + "/cancel", "{\"reason\":\"Error\"}", reception, key())
                .andExpect(status().isForbidden());
        assertThat(count("financial_movement")).isEqualTo(1);
    }

    @Test
    void dentistSoloLeeSuAtencionPropiaYNoCreaNiPagaNiCorrige() throws Exception {
        Long id = create(chargeBody(ownAppointmentId, dentistId, "100"), owner);
        pay(id, "40", owner, key());
        read("/api/charges/" + id, dentist).andExpect(status().isOk()).andExpect(jsonPath("$.pendingBalance").value(60));
        read("/api/charges/" + id + "/items", dentist).andExpect(status().isOk());
        read("/api/charges/" + id + "/movements", dentist).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].performedByUserId").value(owner.userId()));
        write("/api/charges", chargeBody(ownAppointmentId, dentistId, "100").toString(), dentist, key())
                .andExpect(status().isForbidden());
        write("/api/charges/" + id + "/payments", "{\"method\":\"CASH\",\"amount\":10}", dentist, key())
                .andExpect(status().isForbidden());
        write(reversePath(id, 1L), "{\"reason\":\"Error\"}", dentist, key())
                .andExpect(status().isForbidden());
        write("/api/charges/" + id + "/cancel", "{\"reason\":\"Error\"}", dentist, key())
                .andExpect(status().isForbidden());
    }

    @Test
    void dentistNoLeeCargosAjenosMixtosOSinCitaYSuEscrituraEs403() throws Exception {
        Charge foreign = operations.confirmCharge(command(otherAppointmentId, dentistId, "100", key()), owner);
        Charge noLink = operations.confirmCharge(command(null, dentistId, "100", key()), owner);
        Charge mixed = operations.confirmCharge(new ConfirmChargeCommand(patientId, ownAppointmentId,
                List.of(item(dentistId, "Propio", 1, "50"), item(otherDentistId, "Ajeno", 1, "50")), null, key()), owner);
        for (Charge charge : List.of(foreign, noLink, mixed)) {
            for (String suffix : List.of("", "/items", "/movements")) {
                read("/api/charges/" + charge.getId() + suffix, dentist).andExpect(status().isNotFound());
            }
            write("/api/charges/" + charge.getId() + "/payments", "{\"method\":\"CASH\",\"amount\":1}", dentist, key())
                    .andExpect(status().isForbidden());
        }
        for (ObjectNode body : List.of(chargeBody(otherAppointmentId, dentistId, "100"),
                chargeBody(null, dentistId, "100"), chargeBody(ownAppointmentId, otherDentistId, "100"))) {
            write("/api/charges", body.toString(), dentist, key()).andExpect(status().isForbidden());
        }
        assertThat(count("financial_movement")).isZero();
    }

    @Test
    void pacienteDenegadoEnTodasLasRutas() throws Exception {
        Charge charge = confirmed("100");
        long payment = payment(charge, "10", key()).getId();
        for (String path : List.of("/api/charges", "/api/charges/" + charge.getId(),
                "/api/charges/" + charge.getId() + "/items", "/api/charges/" + charge.getId() + "/movements")) {
            read(path, patientActor).andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        }
        write("/api/charges", chargeBody(null, null, "100").toString(), patientActor, key()).andExpect(status().isForbidden());
        write("/api/charges/" + charge.getId() + "/payments", "{\"method\":\"CASH\",\"amount\":1}", patientActor, key())
                .andExpect(status().isForbidden());
        write(reversePath(charge.getId(), payment), "{\"reason\":\"Error\"}", patientActor, key()).andExpect(status().isForbidden());
        write("/api/charges/" + charge.getId() + "/cancel", "{\"reason\":\"Error\"}", patientActor, key())
                .andExpect(status().isForbidden());
    }

    @Test
    void ausenciaDeAutenticacionOJwtInvalidoDevuelve401InclusoEnEscrituras() throws Exception {
        for (String path : List.of("/api/charges", "/api/charges/1", "/api/charges/1/items", "/api/charges/1/movements")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        }
        for (String path : List.of("/api/charges", "/api/charges/1/payments", "/api/charges/1/cancel", reversePath(1L, 1L))) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/charges").header("Authorization", "Bearer invalido")).andExpect(status().isUnauthorized());
        String bearer = token(owner);
        jdbc.update("UPDATE users SET active = FALSE WHERE id = ?", owner.userId());
        mvc.perform(get("/api/charges").header("Authorization", "Bearer " + bearer)).andExpect(status().isUnauthorized());
    }

    @Test
    void rolPersistidoPrevaleceSobreClaimJwtYSobreCamposCliente() throws Exception {
        String bearer = token(owner);
        jdbc.update("UPDATE users SET role = 'PATIENT' WHERE id = ?", owner.userId());
        mvc.perform(get("/api/charges").header("Authorization", "Bearer " + bearer)).andExpect(status().isForbidden());
        ObjectNode forged = chargeBody(ownAppointmentId, dentistId, "100").put("dentistId", otherDentistId);
        write("/api/charges", forged.toString(), reception, key()).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"EMPTY", "NULL_ITEM", "BLANK", "ZERO", "NEGATIVE", "DECIMALS", "QUANTITY_ZERO", "QUANTITY_FRACTION", "PATIENT_FRACTION", "TOO_LARGE"})
    void altaInvalidaEs400SinCargosNiClaveConsumida(String scenario) throws Exception {
        ObjectNode body = chargeBody(null, null, "100");
        ObjectNode item = (ObjectNode) body.get("items").get(0);
        switch (scenario) {
            case "EMPTY" -> body.putArray("items");
            case "NULL_ITEM" -> body.putArray("items").addNull();
            case "BLANK" -> item.put("description", " ");
            case "ZERO" -> item.put("unitPrice", BigDecimal.ZERO);
            case "NEGATIVE" -> item.put("unitPrice", new BigDecimal("-1"));
            case "DECIMALS" -> item.put("unitPrice", new BigDecimal("1.001"));
            case "QUANTITY_ZERO" -> item.put("quantity", 0);
            case "QUANTITY_FRACTION" -> item.put("quantity", new BigDecimal("1.5"));
            case "PATIENT_FRACTION" -> body.put("patientId", new BigDecimal("1.5"));
            case "TOO_LARGE" -> { item.put("unitPrice", new BigDecimal("999999999999999.99")); item.put("quantity", 2); }
            default -> throw new AssertionError(scenario);
        }
        JsonNode error = json(write("/api/charges", body.toString(), owner, "bad").andExpect(status().isBadRequest()));
        assertThat(error.get("status").intValue()).isEqualTo(400);
        assertThat(error.get("path").asText()).isEqualTo("/api/charges");
        assertThat(error.has("timestamp")).isTrue();
        assertThat(count("charge")).isZero();
        assertThat(count("charge_item")).isZero();
        assertThat(count("http_idempotency")).isZero();
    }

    @Test
    void camposDesconocidosNoSeIgnoranNiPermitenSolicitarReversaParcial() throws Exception {
        ObjectNode extra = chargeBody(null, null, "100").put("totalAmount", 1);
        write("/api/charges", extra.toString(), owner, key()).andExpect(status().isBadRequest());
        ObjectNode nested = chargeBody(null, null, "100");
        ((ObjectNode) nested.get("items").get(0)).put("invented", "dato ignorado");
        write("/api/charges", nested.toString(), owner, key()).andExpect(status().isBadRequest());
        Long id = create(chargeBody(null, null, "100"), owner);
        long original = pay(id, "100", owner, key()).get("id").longValue();
        write(reversePath(id, original), "{\"reason\":\"Error\",\"amount\":1}", owner, key())
                .andExpect(status().isBadRequest());
        write("/api/charges/" + id + "/payments", "{\"method\":\"CASH\",\"amount\":1,\"patientId\":1}", owner, key())
                .andExpect(status().isBadRequest());
        assertThat(count("financial_movement")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{}", "{\"method\":\"WIRE\",\"amount\":1}", "{\"method\":\"CASH\",\"amount\":0}",
            "{\"method\":\"CASH\",\"amount\":-1}", "{\"method\":\"CASH\",\"amount\":1.001}",
            "{\"method\":1,\"amount\":1}", "{\"method\":true,\"amount\":1}"})
    void pagoInvalidoDevuelve400SinFiltrarInternals(String body) throws Exception {
        Charge charge = confirmed("100");
        JsonNode error = json(write("/api/charges/" + charge.getId() + "/payments", body, owner, key())
                .andExpect(status().isBadRequest()));
        assertThat(error.toString()).doesNotContain("SQLException", "stackTrace", "financial_movement", "org.springframework");
        assertThat(count("financial_movement")).isZero();
    }

    @Test
    void sobrepagoDobleReversaYAnulacionConPagosSon409() throws Exception {
        Long id = create(chargeBody(null, null, "100"), owner);
        write("/api/charges/" + id + "/payments", "{\"method\":\"CASH\",\"amount\":100.01}", owner, key())
                .andExpect(status().isConflict());
        long movementId = pay(id, "40", owner, key()).get("id").longValue();
        write("/api/charges/" + id + "/cancel", "{\"reason\":\"Error\"}", owner, key()).andExpect(status().isConflict());
        write(reversePath(id, movementId), "{\"reason\":\"Error\"}", owner, key()).andExpect(status().isCreated());
        write(reversePath(id, movementId), "{\"reason\":\"Otra reversa\"}", owner, key()).andExpect(status().isConflict());
        write("/api/charges/" + id + "/cancel", "{\"reason\":\"Error\"}", owner, key()).andExpect(status().isOk());
        write("/api/charges/" + id + "/payments", "{\"method\":\"CASH\",\"amount\":1}", owner, key())
                .andExpect(status().isConflict());
    }

    @Test
    void lasCuatroEscriturasExigenCabeceraIdempotente() throws Exception {
        Charge charge = confirmed("100");
        long original = payment(charge, "10", key()).getId();
        for (String missingKey : java.util.Arrays.asList(null, " ")) {
            write("/api/charges", chargeBody(null, null, "100").toString(), owner, missingKey).andExpect(status().isBadRequest());
            write("/api/charges/" + charge.getId() + "/payments", "{\"method\":\"CASH\",\"amount\":1}", owner, missingKey)
                    .andExpect(status().isBadRequest());
            write(reversePath(charge.getId(), original), "{\"reason\":\"Error\"}", owner, missingKey).andExpect(status().isBadRequest());
            write("/api/charges/" + charge.getId() + "/cancel", "{\"reason\":\"Error\"}", owner, missingKey)
                    .andExpect(status().isBadRequest());
        }
        assertThat(count("charge")).isEqualTo(1);
        assertThat(count("financial_movement")).isEqualTo(1);
    }

    @Test
    void reintentosHttpConservanResultadoOriginalYNoCambianSnapshots() throws Exception {
        String confirmBody = chargeBody(null, null, "100.01").toString();
        JsonNode confirmed = json(write("/api/charges", confirmBody, owner, "confirm").andExpect(status().isCreated()));
        long id = confirmed.get("id").longValue();
        String payBody = "{\"method\":\"CARD\",\"amount\":100.01}";
        JsonNode paid = json(write("/api/charges/" + id + "/payments", payBody, owner, "pay").andExpect(status().isCreated()));
        String reversalPath = reversePath(id, paid.get("id").longValue());
        JsonNode reversed = json(write(reversalPath, "{\"reason\":\"Error\"}", owner, "reverse").andExpect(status().isCreated()));
        JsonNode cancelled = json(write("/api/charges/" + id + "/cancel", "{\"reason\":\"Error\"}", owner, "cancel")
                .andExpect(status().isOk()));
        List<String> stored = jdbc.queryForList("SELECT response_body::text FROM http_idempotency ORDER BY id", String.class);

        assertThat(json(write("/api/charges", confirmBody, owner, "confirm").andExpect(status().isCreated())))
                .isEqualTo(confirmed);
        assertThat(json(write("/api/charges/" + id + "/payments", payBody, owner, "pay").andExpect(status().isCreated())))
                .isEqualTo(paid);
        assertThat(json(write(reversalPath, "{\"reason\":\"Error\"}", owner, "reverse").andExpect(status().isCreated())))
                .isEqualTo(reversed);
        assertThat(json(write("/api/charges/" + id + "/cancel", "{\"reason\":\"Error\"}", owner, "cancel")
                .andExpect(status().isOk()))).isEqualTo(cancelled);
        assertThat(jdbc.queryForList("SELECT response_body::text FROM http_idempotency ORDER BY id", String.class)).isEqualTo(stored);
        assertThat(count("charge")).isEqualTo(1);
        assertThat(count("financial_movement")).isEqualTo(2);
        assertThat(count("http_idempotency")).isEqualTo(4);
        read("/api/charges/" + id, owner).andExpect(jsonPath("$.status").value("ANULADO"));
    }

    @Test
    void ordenDeCamposJsonYWhitespaceNoCambianHashPeroLosValoresSi() throws Exception {
        String original = chargeBody(null, null, "100.01").toString();
        JsonNode first = json(write("/api/charges", original, owner, "same").andExpect(status().isCreated()));
        String reordered = "{\n\"items\":[{\"unitPrice\":100.01,\"quantity\":1,\"description\":\"Concepto historico\"}],"
                + "\"patientId\":" + patientId + "}";
        assertThat(json(write("/api/charges", reordered, owner, "same").andExpect(status().isCreated()))).isEqualTo(first);
        write("/api/charges", chargeBody(null, null, "100.02").toString(), owner, "same").andExpect(status().isConflict());
        assertThat(count("charge")).isEqualTo(1);
    }

    @Test
    void conflictosDeClaveHttpParaPagoReversaYAnulacionSon409() throws Exception {
        Long id = create(chargeBody(null, null, "100"), owner);
        long original = pay(id, "10", owner, "pay").get("id").longValue();
        write("/api/charges/" + id + "/payments", "{\"method\":\"CASH\",\"amount\":11}", owner, "pay")
                .andExpect(status().isConflict());
        write(reversePath(id, original), "{\"reason\":\"A\"}", owner, "reverse").andExpect(status().isCreated());
        write(reversePath(id, original), "{\"reason\":\"B\"}", owner, "reverse").andExpect(status().isConflict());
        write("/api/charges/" + id + "/cancel", "{\"reason\":\"A\"}", owner, "cancel").andExpect(status().isOk());
        write("/api/charges/" + id + "/cancel", "{\"reason\":\"B\"}", owner, "cancel").andExpect(status().isConflict());
    }

    @Test
    void dentistLeeSoloConVinculoPersistidoYSeRevalidaAunqueElJwtSigaVigente() throws Exception {
        Long id = create(chargeBody(ownAppointmentId, dentistId, "100"), owner);
        read("/api/charges/" + id, dentist).andExpect(status().isOk());
        jdbc.update("UPDATE appointment SET dentist_id = ? WHERE id = ?", otherDentistId, ownAppointmentId);
        read("/api/charges/" + id, dentist).andExpect(status().isNotFound());
    }

    @Test
    void listadosFiltranAntesDePaginarYContarSinDuplicarCargosConVariosConceptos() throws Exception {
        List<Long> visible = new ArrayList<>();
        for (int n = 0; n < 3; n++) {
            visible.add(operations.confirmCharge(new ConfirmChargeCommand(patientId, ownAppointmentId,
                    List.of(item(dentistId, "A", 1, "50"), item(null, "B", 1, "50")), null, key()), owner).getId());
        }
        operations.confirmCharge(command(otherAppointmentId, dentistId, "100", key()), owner);
        operations.confirmCharge(command(null, dentistId, "100", key()), owner);
        operations.confirmCharge(new ConfirmChargeCommand(patientId, ownAppointmentId,
                List.of(item(dentistId, "A", 1, "50"), item(otherDentistId, "B", 1, "50")), null, key()), owner);
        jdbc.update("INSERT INTO charge (patient_id, appointment_id, total_amount, confirmed_at, confirmed_by_user_id) "
                + "VALUES (?, ?, 100, now(), ?), (?, ?, 100, now(), ?)", patientId, ownAppointmentId, owner.userId(),
                otherPatientId, ownAppointmentId, owner.userId()); // Sin conceptos / paciente inconsistente: fail-closed.
        for (int page = 0; page < 3; page++) {
            JsonNode response = json(read("/api/charges?page=" + page + "&size=1&dentistId=" + otherDentistId, dentist)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.totalPages").value(3)).andExpect(jsonPath("$.content.length()").value(1)));
            assertThat(response.get("content").get(0).get("id").longValue()).isEqualTo(visible.get(2 - page));
        }
        read("/api/charges?page=3&size=1", dentist).andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(3));
        read("/api/charges?patientId=" + otherPatientId, dentist).andExpect(jsonPath("$.totalElements").value(0));
        read("/api/charges?appointmentId=" + otherAppointmentId, dentist).andExpect(jsonPath("$.totalElements").value(0));
        read("/api/charges?size=100", owner).andExpect(jsonPath("$.totalElements").value(8));
        read("/api/charges?size=100", reception).andExpect(jsonPath("$.totalElements").value(8));
    }

    @Test
    void filtrosDeEstadoPacienteCitaEIntervaloCoincidenConSaldosDerivados() throws Exception {
        Long pending = create(chargeBody(ownAppointmentId, dentistId, "100"), owner);
        Long partial = create(chargeBody(ownAppointmentId, dentistId, "100"), owner);
        Long paid = create(chargeBody(ownAppointmentId, dentistId, "100"), owner);
        Long cancelled = create(chargeBody(ownAppointmentId, dentistId, "100"), owner);
        pay(partial, "25", owner, key());
        pay(paid, "100", owner, key());
        write("/api/charges/" + cancelled + "/cancel", "{\"reason\":\"Error\"}", owner, key()).andExpect(status().isOk());
        String[] statuses = {"PENDIENTE", "PARCIAL", "PAGADO", "ANULADO"};
        List<Long> ids = List.of(pending, partial, paid, cancelled);
        for (int n = 0; n < statuses.length; n++) {
            read("/api/charges?status=" + statuses[n] + "&patientId=" + patientId + "&appointmentId=" + ownAppointmentId
                    + "&from=2000-01-01T00:00:00Z&to=2100-01-01T00:00:00Z", dentist)
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].id").value(ids.get(n)))
                    .andExpect(jsonPath("$.content[0].status").value(statuses[n]));
        }
        read("/api/charges?from=2100-01-01T00:00:00Z", owner).andExpect(jsonPath("$.totalElements").value(0));
        read("/api/charges?size=1000&page=-1", owner).andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.page").value(0));
    }

    @Test
    void dentistSinPerfilProfesionalObtienePaginaVaciaYSinTotalesAjenos() throws Exception {
        confirmed("100");
        jdbc.update("DELETE FROM appointment");
        jdbc.update("DELETE FROM dentist WHERE id = ?", dentistId);
        read("/api/charges", dentist).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void formatosInvalidosSon400YRecursoInexistenteEs404() throws Exception {
        for (String path : List.of("/api/charges/abc", "/api/charges/0", "/api/charges?status=INVALID",
                "/api/charges?patientId=abc", "/api/charges?patientId=-1", "/api/charges?from=no-fecha",
                "/api/charges?from=2100-01-01T00:00:00Z&to=2000-01-01T00:00:00Z")) {
            JsonNode error = json(read(path, owner).andExpect(status().isBadRequest()));
            assertThat(error.toString()).doesNotContain("SQL", "org.springframework", "stackTrace");
        }
        read("/api/charges/" + Long.MAX_VALUE, owner).andExpect(status().isNotFound());
    }

    @Test
    void precisionMonetariaGrandeSeSerializaSinDouble() throws Exception {
        Long id = create(chargeBody(null, null, "99999999999999.99"), owner);
        JsonNode detail = json(read("/api/charges/" + id, owner).andExpect(status().isOk()));
        assertThat(detail.get("totalAmount").decimalValue()).isEqualByComparingTo("99999999999999.99");
        assertThat(detail.get("items").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("99999999999999.99");
        assertThat(detail.has("patient")).isFalse(); // Solo IDs, ninguna entidad/expediente de paciente serializados.
        assertThat(detail.has("passwordHash")).isFalse();
    }

    @Test
    void falloDeIntegridadDevuelve409GenericoYReviertePago() throws Exception {
        Charge charge = confirmed("100");
        jdbc.execute("ALTER TABLE http_idempotency ADD CONSTRAINT financial_api_test_fault "
                + "CHECK (idempotency_key <> 'force-fault')");
        try {
            JsonNode error = json(write("/api/charges/" + charge.getId() + "/payments",
                    "{\"method\":\"CASH\",\"amount\":10}", owner, "force-fault").andExpect(status().isConflict()));
            assertThat(error.toString()).doesNotContain("financial_api_test_fault", "http_idempotency", "SQL", "stackTrace");
            assertThat(count("financial_movement")).isZero();
        } finally {
            jdbc.execute("ALTER TABLE http_idempotency DROP CONSTRAINT financial_api_test_fault");
        }
    }

    @Test
    void corsAceptaCabeceraIdempotenteSinAmpliarOrigenes() throws Exception {
        mvc.perform(options("/api/charges").header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Authorization,Content-Type,Idempotency-Key"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/api/charges").header("Origin", "https://no-autorizado.test")
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden());
    }
}

package com.dentalclinic.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.dentalclinic.financial.application.AppointmentFinancialContextService;
import com.dentalclinic.financial.application.command.CancelChargeCommand;
import com.dentalclinic.financial.application.command.ConfirmChargeCommand;
import com.dentalclinic.financial.application.command.RegisterPaymentCommand;
import com.dentalclinic.financial.application.command.ReversePaymentCommand;
import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.FinancialMovement;
import com.dentalclinic.financial.domain.PaymentMethod;
import com.dentalclinic.identity.application.UserService;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.identity.infrastructure.security.JwtTokenService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Contexto financiero de una atencion (UX-05B.1, Etapa 2 — <strong>solo lectura</strong>).
 *
 * <p>MockMvc, filtros JWT/rol reales, servicios reales y PostgreSQL 16 efimero con V001..V019.
 * La base es efimera: nunca se conecta a {@code 127.0.0.1:5434}.
 *
 * <p>Los casos cubren la cardinalidad (sin cargos / uno / varios), los estados derivados
 * (pendiente, parcial, pagado, anulado), los pagos revertidos, la ausencia de truncamiento con
 * mas de 100 cargos, la exclusion de cargos ajenos, el 409 generico ante conjuntos no verificables
 * y los cuatro roles por HTTP y por llamada directa al servicio.
 */
class AppointmentFinancialContextApiTest extends FinancialOperationsIntegrationSupport {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private UserService users;
    @Autowired private JwtTokenService jwt;
    @Autowired private AppointmentFinancialContextService contexts;
    @Autowired private java.time.Clock clock;

    private String token(AuthenticatedUser actor) {
        return jwt.issue(users.getById(actor.userId()), clock.instant()).value();
    }

    private String contextPath(Long appointmentId) {
        return "/api/appointments/" + appointmentId + "/financial-context";
    }

    private ResultActions read(String path, AuthenticatedUser actor) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + token(actor)));
    }

    private ResultActions readContext(Long appointmentId, AuthenticatedUser actor) throws Exception {
        return read(contextPath(appointmentId), actor);
    }

    private JsonNode json(ResultActions result) throws Exception {
        return mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readTree(result.andReturn().getResponse().getContentAsString());
    }

    private Charge charge(Long appointment, Long professional, String amount) {
        return operations.confirmCharge(command(appointment, professional, amount, key()), owner);
    }

    private Long insertCharge(Long patient, Long appointment, String total) {
        return jdbc.queryForObject("INSERT INTO charge (patient_id, appointment_id, total_amount, "
                + "confirmed_at, confirmed_by_user_id) VALUES (?, ?, ?, now(), ?) RETURNING id",
                Long.class, patient, appointment, new BigDecimal(total), owner.userId());
    }

    private void insertItem(Long chargeId, String description, int quantity, String unitPrice) {
        jdbc.update("INSERT INTO charge_item (charge_id, description, quantity, unit_price, line_total) "
                + "VALUES (?, ?, ?, ?, ?)", chargeId, description, quantity,
                new BigDecimal(unitPrice), new BigDecimal(unitPrice).multiply(BigDecimal.valueOf(quantity)));
    }

    private Long insertPayment(Long chargeId, Long patient, String amount) {
        return jdbc.queryForObject("INSERT INTO financial_movement (charge_id, patient_id, type, direction, "
                + "payment_method, amount, performed_by_user_id, performed_at) "
                + "VALUES (?, ?, 'PAYMENT', 'I', 'CASH', ?, ?, now()) RETURNING id",
                Long.class, chargeId, patient, new BigDecimal(amount), owner.userId());
    }

    private Long insertReversal(Long chargeId, Long patient, Long targetId, String amount) {
        return jdbc.queryForObject("INSERT INTO financial_movement (charge_id, patient_id, type, direction, "
                + "amount, reason, reverses_movement_id, performed_by_user_id, performed_at) "
                + "VALUES (?, ?, 'REVERSAL', 'O', ?, 'Correccion de cobro', ?, ?, now()) RETURNING id",
                Long.class, chargeId, patient, new BigDecimal(amount), targetId, owner.userId());
    }

    private void expectUnavailable(Long appointmentId, AuthenticatedUser actor) throws Exception {
        JsonNode body = json(readContext(appointmentId, actor).andExpect(status().isConflict())
                .andExpect(header().string("Cache-Control", "no-store")));
        assertThat(body.get("code").asText()).isEqualTo("FINANCIAL_CONTEXT_UNAVAILABLE");
        assertThat(body.has("charges")).isFalse();
    }

    // ------------------------------------------------------------------
    // Cardinalidad
    // ------------------------------------------------------------------

    @Test
    void sinCargosLaVistaEstaVaciaYNoInventaDeuda() throws Exception {
        ResultActions result = readContext(ownAppointmentId, owner).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.appointmentId").value(ownAppointmentId))
                .andExpect(jsonPath("$.patientId").value(patientId))
                .andExpect(jsonPath("$.dentistId").value(dentistId))
                .andExpect(jsonPath("$.associationState").value("NO_ASSOCIATED_CHARGE"))
                .andExpect(jsonPath("$.chargeCount").value(0))
                .andExpect(jsonPath("$.charges").isEmpty());
        JsonNode body = json(result);
        // Vacio no es saldo cero (C-04): el nivel superior no lleva importes agregados.
        assertThat(body.has("totalAmount")).isFalse();
        assertThat(body.has("appliedAmount")).isFalse();
        assertThat(body.has("pendingBalance")).isFalse();
        assertThat(body.has("selectedChargeId")).isFalse();
    }

    @Test
    void unUnicoCargoActivoSeClasificaComoSingleActiveYExponeConceptosYSaldos() throws Exception {
        Charge charge = charge(ownAppointmentId, dentistId, "100.30");
        JsonNode body = json(readContext(ownAppointmentId, owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.associationState").value("SINGLE_ACTIVE"))
                .andExpect(jsonPath("$.chargeCount").value(1)));
        JsonNode cargo = body.get("charges").get(0);
        assertThat(cargo.get("id").asLong()).isEqualTo(charge.getId());
        assertThat(cargo.get("currency").asText()).isEqualTo("MXN");
        assertThat(cargo.get("totalAmount").decimalValue()).isEqualByComparingTo("100.30");
        assertThat(cargo.get("appliedAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(cargo.get("pendingBalance").decimalValue()).isEqualByComparingTo("100.30");
        assertThat(cargo.get("status").asText()).isEqualTo("PENDIENTE");
        assertThat(cargo.get("items").size()).isEqualTo(1);
        assertThat(cargo.get("items").get(0).get("description").asText()).isEqualTo("Concepto acordado");
        assertThat(cargo.get("items").get(0).get("lineTotal").decimalValue()).isEqualByComparingTo("100.30");
        assertThat(cargo.get("movements").isEmpty()).isTrue();
    }

    @Test
    void variosCargosActivosSeClasificanComoMultipleActive() throws Exception {
        Charge first = charge(ownAppointmentId, dentistId, "10");
        Charge second = charge(ownAppointmentId, dentistId, "20");
        JsonNode body = json(readContext(ownAppointmentId, owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.associationState").value("MULTIPLE_ACTIVE"))
                .andExpect(jsonPath("$.chargeCount").value(2)));
        assertThat(body.get("charges").get(0).get("id").asLong()).isEqualTo(first.getId());
        assertThat(body.get("charges").get(1).get("id").asLong()).isEqualTo(second.getId());
    }

    @Test
    void soloCargosAnuladosSeClasificanComoOnlyCancelled() throws Exception {
        Charge cancelled = charge(ownAppointmentId, dentistId, "10");
        operations.cancelCharge(new CancelChargeCommand(cancelled.getId(), "Anulacion de prueba", key()), owner);
        readContext(ownAppointmentId, owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.associationState").value("ONLY_CANCELLED"))
                .andExpect(jsonPath("$.chargeCount").value(1))
                .andExpect(jsonPath("$.charges[0].status").value("ANULADO"))
                .andExpect(jsonPath("$.charges[0].pendingBalance").value(0));
    }

    @Test
    void unCargoAnuladoYOtroActivoSeClasificanComoSingleActiveSinOcultarHistoria() throws Exception {
        Charge cancelled = charge(ownAppointmentId, dentistId, "10");
        operations.cancelCharge(new CancelChargeCommand(cancelled.getId(), "Anulacion de prueba", key()), owner);
        charge(ownAppointmentId, dentistId, "20");
        readContext(ownAppointmentId, owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.associationState").value("SINGLE_ACTIVE"))
                .andExpect(jsonPath("$.chargeCount").value(2));
    }

    // ------------------------------------------------------------------
    // Estados derivados y movimientos
    // ------------------------------------------------------------------

    @Test
    void losEstadosPendienteParcialPagadoYAnuladoSeDerivanSinPersistirse() throws Exception {
        Charge pending = charge(ownAppointmentId, dentistId, "100");
        Charge partial = charge(ownAppointmentId, dentistId, "100");
        Charge paid = charge(ownAppointmentId, dentistId, "100");
        Charge cancelled = charge(ownAppointmentId, dentistId, "100");
        operations.registerPayment(new RegisterPaymentCommand(partial.getId(), PaymentMethod.CASH,
                new BigDecimal("25"), null, key()), owner);
        operations.registerPayment(new RegisterPaymentCommand(paid.getId(), PaymentMethod.CARD,
                new BigDecimal("100"), null, key()), owner);
        operations.cancelCharge(new CancelChargeCommand(cancelled.getId(), "Anulacion de prueba", key()), owner);

        JsonNode body = json(readContext(ownAppointmentId, owner).andExpect(status().isOk()));
        assertThat(statusOf(body, pending.getId())).isEqualTo("PENDIENTE");
        assertThat(statusOf(body, partial.getId())).isEqualTo("PARCIAL");
        assertThat(statusOf(body, paid.getId())).isEqualTo("PAGADO");
        assertThat(statusOf(body, cancelled.getId())).isEqualTo("ANULADO");
        JsonNode partialCargo = chargeOf(body, partial.getId());
        assertThat(partialCargo.get("appliedAmount").decimalValue()).isEqualByComparingTo("25");
        assertThat(partialCargo.get("pendingBalance").decimalValue()).isEqualByComparingTo("75");
        JsonNode paidCargo = chargeOf(body, paid.getId());
        assertThat(paidCargo.get("pendingBalance").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void unPagoRevertidoNoCuentaComoAplicado() throws Exception {
        Charge charge = charge(ownAppointmentId, dentistId, "100");
        FinancialMovement payment = operations.registerPayment(new RegisterPaymentCommand(charge.getId(),
                PaymentMethod.CASH, new BigDecimal("40"), null, key()), owner);
        operations.reversePayment(new ReversePaymentCommand(charge.getId(), payment.getId(),
                "Error de cobro", key()), owner);
        JsonNode cargo = chargeOf(json(readContext(ownAppointmentId, owner).andExpect(status().isOk())),
                charge.getId());
        assertThat(cargo.get("status").asText()).isEqualTo("PENDIENTE");
        assertThat(cargo.get("appliedAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(cargo.get("pendingBalance").decimalValue()).isEqualByComparingTo("100");
        assertThat(cargo.get("movements").size()).isEqualTo(2);
    }

    @Test
    void losCamposDeObservacionYVersionDeOcurrenciaSeExponenSinSeleccionarCargo() throws Exception {
        Long serviceId = jdbc.queryForObject("INSERT INTO service_catalog_item (name, description, "
                + "reference_price, duration_minutes) VALUES ('Limpieza', 'Limpieza dental', 450.00, 60) "
                + "RETURNING id", Long.class);
        jdbc.update("UPDATE appointment SET service_id = ?, service_name = 'Limpieza', "
                + "service_description = 'Limpieza dental', service_reference_price = 450.00, "
                + "service_duration_minutes = 60, occurrence_version = 2 WHERE id = ?",
                serviceId, ownAppointmentId);
        Instant before = clock.instant();
        JsonNode body = json(readContext(ownAppointmentId, owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.appointmentOccurrenceVersion").value(2))
                .andExpect(jsonPath("$.observedAt").isNotEmpty())
                .andExpect(jsonPath("$.service.id").value(serviceId))
                .andExpect(jsonPath("$.service.name").value("Limpieza"))
                .andExpect(jsonPath("$.service.durationMinutes").value(60)));
        Instant observedAt = Instant.parse(body.get("observedAt").asText());
        // Jackson puede truncar la fraccion de nanosegundos: se usa una ventana tecnica, no igualdad exacta.
        assertThat(observedAt).isBetween(before.minusSeconds(1), clock.instant().plusSeconds(1));
        assertThat(body.get("service").get("referencePrice").decimalValue())
                .isEqualByComparingTo("450.00");
        assertThat(body.has("selectedChargeId")).isFalse();
    }

    @Test
    void sinServicioCapturadoElSnapshotEsNuloYNoSeInventa() throws Exception {
        JsonNode body = json(readContext(ownAppointmentId, owner).andExpect(status().isOk()));
        assertThat(body.has("service")).isFalse();
        assertThat(body.get("appointmentOccurrenceVersion").asInt()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Integridad de conceptos y movimientos (auditoria I-01/I-02)
    // ------------------------------------------------------------------

    @Test
    void unMovimientoDeOtroPacienteEsInconsistenteParaTodosLosRolesAdministrativos() throws Exception {
        Charge charge = charge(ownAppointmentId, dentistId, "100");
        insertPayment(charge.getId(), otherPatientId, "30");
        for (AuthenticatedUser actor : List.of(owner, reception)) {
            expectUnavailable(ownAppointmentId, actor);
        }
    }

    @Test
    void unaReversaQueApuntaAUnPagoDeOtroCargoEsInconsistenteEnAmbosCargos() throws Exception {
        Charge own = charge(ownAppointmentId, dentistId, "100");
        Charge other = charge(otherAppointmentId, otherDentistId, "100");
        Long payment = insertPayment(other.getId(), patientId, "40");
        insertReversal(own.getId(), patientId, payment, "40");
        expectUnavailable(ownAppointmentId, owner);
        expectUnavailable(otherAppointmentId, owner);
    }

    @Test
    void unaReversaConImporteDistintoAlPagoEsInconsistenteParaTodosLosRoles() throws Exception {
        Charge charge = charge(ownAppointmentId, dentistId, "100");
        Long payment = insertPayment(charge.getId(), patientId, "40");
        insertReversal(charge.getId(), patientId, payment, "30");
        for (AuthenticatedUser actor : List.of(owner, reception)) {
            expectUnavailable(ownAppointmentId, actor);
        }
    }

    @Test
    void unCargoSinConceptosNoEsVerificableNiParaOwnerNiParaRecepcion() throws Exception {
        insertCharge(patientId, ownAppointmentId, "100");
        for (AuthenticatedUser actor : List.of(owner, reception)) {
            expectUnavailable(ownAppointmentId, actor);
        }
    }

    @Test
    void unTotalDistintoDeLaSumaDeConceptosEsInconsistenteParaTodosLosRoles() throws Exception {
        Long chargeId = insertCharge(patientId, ownAppointmentId, "100");
        insertItem(chargeId, "Concepto incompleto", 1, "90");
        for (AuthenticatedUser actor : List.of(owner, reception)) {
            expectUnavailable(ownAppointmentId, actor);
        }
    }

    @Test
    void unCargoConPagosQueCuadranPeroConceptosIncoherentesNoSePresentaComoValido() throws Exception {
        Long chargeId = insertCharge(patientId, ownAppointmentId, "100");
        insertItem(chargeId, "Concepto incompleto", 1, "90");
        insertPayment(chargeId, patientId, "100");
        // El estado derivado por pagos seria PAGADO (100 == 100), pero la suma de conceptos es 90.
        for (AuthenticatedUser actor : List.of(owner, reception)) {
            expectUnavailable(ownAppointmentId, actor);
        }
    }

    @Test
    void unSobrepagoEsInconsistenteParaTodosLosRoles() throws Exception {
        Charge charge = charge(ownAppointmentId, dentistId, "100");
        insertPayment(charge.getId(), patientId, "150");
        for (AuthenticatedUser actor : List.of(owner, reception)) {
            expectUnavailable(ownAppointmentId, actor);
        }
    }

    @Test
    void unCargoVerificableJuntoAOtroNoVerificableNuncaDevuelveResultadoParcial() throws Exception {
        charge(ownAppointmentId, dentistId, "123.45");
        insertCharge(otherPatientId, ownAppointmentId, "50");
        for (AuthenticatedUser actor : List.of(owner, reception, dentist)) {
            JsonNode body = json(readContext(ownAppointmentId, actor).andExpect(status().isConflict()));
            assertThat(body.get("code").asText()).isEqualTo("FINANCIAL_CONTEXT_UNAVAILABLE");
            assertThat(body.has("charges")).isFalse();
            assertThat(body.toString()).doesNotContain("123.45");
        }
    }

    @Test
    void masDeCienCargosNoSeTruncan() throws Exception {
        int total = 105;
        for (int n = 0; n < total; n++) {
            charge(ownAppointmentId, dentistId, "10");
        }
        readContext(ownAppointmentId, owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.chargeCount").value(total))
                .andExpect(jsonPath("$.associationState").value("MULTIPLE_ACTIVE"))
                .andExpect(jsonPath("$.charges.length()").value(total));
        // El mismo conjunto completo para el DENTIST de la cita: su verificacion por cargo no trunca.
        readContext(ownAppointmentId, dentist).andExpect(status().isOk())
                .andExpect(jsonPath("$.chargeCount").value(total))
                .andExpect(jsonPath("$.charges.length()").value(total))
                .andExpect(jsonPath("$.charges[0].id").exists())
                .andExpect(jsonPath("$.charges[" + (total - 1) + "].id").exists());
    }

    // ------------------------------------------------------------------
    // Exclusiones
    // ------------------------------------------------------------------

    @Test
    void losCargosSinCitaYLosDeOtrasCitasQuedanFuera() throws Exception {
        Charge withoutAppointment = operations.confirmCharge(command(null, null, "50", key()), owner);
        Charge otherAppointment = charge(otherAppointmentId, dentistId, "70");
        Charge own = charge(ownAppointmentId, dentistId, "100");
        JsonNode body = json(readContext(ownAppointmentId, owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.chargeCount").value(1)));
        assertThat(body.get("charges").get(0).get("id").asLong()).isEqualTo(own.getId());
        assertThat(body.get("charges")).hasSize(1);
    }

    // ------------------------------------------------------------------
    // 409 por conjunto no verificable
    // ------------------------------------------------------------------

    @Test
    void unCargoDeOtroPacienteEsInconsistenteYDevuelve409SinFiltrarDatos() throws Exception {
        jdbc.update("INSERT INTO charge (patient_id, appointment_id, total_amount, confirmed_at, "
                + "confirmed_by_user_id) VALUES (?, ?, 77.77, now(), ?)",
                otherPatientId, ownAppointmentId, owner.userId());
        JsonNode body = json(readContext(ownAppointmentId, owner).andExpect(status().isConflict())
                .andExpect(header().string("Cache-Control", "no-store")));
        assertThat(body.get("code").asText()).isEqualTo("FINANCIAL_CONTEXT_UNAVAILABLE");
        assertThat(body.get("status").asInt()).isEqualTo(409);
        assertThat(body.get("path").asText()).isEqualTo(contextPath(ownAppointmentId));
        assertThat(body.toString()).doesNotContain("77.77", "Concepto", "SQL", "stackTrace",
                "org.springframework", "charge_item", "financial_movement");
        assertThat(body.has("charges")).isFalse();
    }

    @Test
    void unCargoAnuladoQueConservaPagosValidosEsInconsistenteYDevuelve409() throws Exception {
        Charge charge = charge(ownAppointmentId, dentistId, "100");
        operations.cancelCharge(new CancelChargeCommand(charge.getId(), "Anulacion de prueba", key()), owner);
        jdbc.update("INSERT INTO financial_movement (charge_id, patient_id, type, direction, "
                + "payment_method, amount, performed_by_user_id, performed_at) "
                + "VALUES (?, ?, 'PAYMENT', 'I', 'CASH', 10, ?, now())",
                charge.getId(), patientId, owner.userId());
        JsonNode body = json(readContext(ownAppointmentId, owner).andExpect(status().isConflict()));
        assertThat(body.get("code").asText()).isEqualTo("FINANCIAL_CONTEXT_UNAVAILABLE");
        assertThat(body.toString()).doesNotContain("ANULADO", "PARCIAL", "PAGADO", "SQL", "stackTrace");
    }

    @Test
    void unCargoMixtoEsParcialmenteVisibleParaElDentistaYDevuelve409() throws Exception {
        operations.confirmCharge(new ConfirmChargeCommand(patientId, ownAppointmentId,
                List.of(item(dentistId, "Concepto propio", 1, "111.11"),
                        item(otherDentistId, "Concepto ajeno", 1, "222.22")),
                null, key()), owner);
        // El OWNER si ve el conjunto completo: no es un dato corrupto, es un dato mixto.
        readContext(ownAppointmentId, owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.chargeCount").value(1));
        JsonNode body = json(readContext(ownAppointmentId, dentist).andExpect(status().isConflict()));
        assertThat(body.get("code").asText()).isEqualTo("FINANCIAL_CONTEXT_UNAVAILABLE");
        assertThat(body.toString()).doesNotContain("111.11", "222.22", "Concepto propio",
                "Concepto ajeno", "SQL", "stackTrace");
        assertThat(body.has("charges")).isFalse();
    }

    // ------------------------------------------------------------------
    // Autorizacion por rol
    // ------------------------------------------------------------------

    @Test
    void ownerYRecepcionistaMantienenLaLecturaAdministrativa() throws Exception {
        charge(ownAppointmentId, dentistId, "100");
        for (AuthenticatedUser actor : List.of(owner, reception)) {
            readContext(ownAppointmentId, actor).andExpect(status().isOk())
                    .andExpect(jsonPath("$.associationState").value("SINGLE_ACTIVE"));
        }
    }

    @Test
    void elDentistaSoloConsultaElContextoDeSuPropiaCita() throws Exception {
        charge(ownAppointmentId, dentistId, "100");
        readContext(ownAppointmentId, dentist).andExpect(status().isOk())
                .andExpect(jsonPath("$.associationState").value("SINGLE_ACTIVE"));
        // Cita ajena: 404, no se revela su existencia.
        readContext(otherAppointmentId, dentist).andExpect(status().isNotFound());
        readContext(Long.MAX_VALUE, dentist).andExpect(status().isNotFound());
    }

    @Test
    void unDentistaSinPerfilNoDistingueUnaCitaExistenteDeUnaInexistente() throws Exception {
        charge(ownAppointmentId, dentistId, "100");
        Long userId = jdbc.queryForObject("INSERT INTO users (email, password_hash, role, active, created_at) "
                + "VALUES ('sin-perfil@financial.test', 'test-not-a-real-password', 'DENTIST', TRUE, now()) "
                + "RETURNING id", Long.class);
        AuthenticatedUser withoutProfile = new AuthenticatedUser(userId, Role.DENTIST);

        // La cita existe (y tiene un cargo): aun asi no puede demostrar pertenencia.
        readContext(ownAppointmentId, withoutProfile).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        // Una cita inexistente responde lo mismo: no se revela cual existe.
        readContext(Long.MAX_VALUE, withoutProfile).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appointment WHERE id = ?", Long.class,
                ownAppointmentId)).isEqualTo(1);
    }

    @Test
    void elPacienteNoAccedeAlContextoFinanciero() throws Exception {
        charge(ownAppointmentId, dentistId, "100");
        readContext(ownAppointmentId, patientActor).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void laAutorizacionSeRepiteEnLaCapaDeAplicacion() {
        charge(ownAppointmentId, dentistId, "100");
        // PATIENT: el servicio lo rechaza aunque la matriz HTTP ya lo negara.
        assertThatThrownBy(() -> contexts.contextFor(ownAppointmentId, patientActor))
                .isInstanceOf(ForbiddenOperationException.class);
        // DENTIST con cita ajena: 404 tambien por llamada directa.
        assertThatThrownBy(() -> contexts.contextFor(otherAppointmentId, dentist))
                .isInstanceOf(NotFoundException.class);
        // OWNER/RECEPTIONIST: lectura administrativa directa.
        assertThat(contexts.contextFor(ownAppointmentId, owner).associationState().name())
                .isEqualTo("SINGLE_ACTIVE");
        assertThat(contexts.contextFor(ownAppointmentId, reception).charges()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // Ausencia de efectos secundarios
    // ------------------------------------------------------------------

    @Test
    void laConsultaNoProduceEfectosSecundariosNiEscrituras() throws Exception {
        Charge charge = charge(ownAppointmentId, dentistId, "100");
        FinancialMovement payment = operations.registerPayment(new RegisterPaymentCommand(charge.getId(),
                PaymentMethod.CASH, new BigDecimal("40"), null, key()), owner);
        operations.reversePayment(new ReversePaymentCommand(charge.getId(), payment.getId(),
                "Error de cobro", key()), owner);

        List<Map<String, Object>> chargeRows = jdbc.queryForList(
                "SELECT id, patient_id, appointment_id, total_amount, cancelled_at, version, updated_at "
                        + "FROM charge ORDER BY id");
        List<Map<String, Object>> itemRows = jdbc.queryForList(
                "SELECT id, charge_id, description, quantity, unit_price, line_total FROM charge_item "
                        + "ORDER BY id");
        List<Map<String, Object>> movementRows = jdbc.queryForList(
                "SELECT id, charge_id, patient_id, type, direction, amount, reverses_movement_id "
                        + "FROM financial_movement ORDER BY id");
        List<Map<String, Object>> appointmentRows = jdbc.queryForList(
                "SELECT id, status, occurrence_version, completed_at, updated_at, service_id, service_name "
                        + "FROM appointment ORDER BY id");
        long idempotency = count("http_idempotency");

        readContext(ownAppointmentId, owner).andExpect(status().isOk());
        readContext(ownAppointmentId, dentist).andExpect(status().isOk());

        assertThat(jdbc.queryForList("SELECT id, patient_id, appointment_id, total_amount, cancelled_at, "
                + "version, updated_at FROM charge ORDER BY id")).isEqualTo(chargeRows);
        assertThat(jdbc.queryForList("SELECT id, charge_id, description, quantity, unit_price, line_total "
                + "FROM charge_item ORDER BY id")).isEqualTo(itemRows);
        assertThat(jdbc.queryForList("SELECT id, charge_id, patient_id, type, direction, amount, "
                + "reverses_movement_id FROM financial_movement ORDER BY id")).isEqualTo(movementRows);
        assertThat(jdbc.queryForList("SELECT id, status, occurrence_version, completed_at, updated_at, "
                + "service_id, service_name FROM appointment ORDER BY id")).isEqualTo(appointmentRows);
        assertThat(count("http_idempotency")).isEqualTo(idempotency);
    }

    @Test
    void laRespuestaNoExponeDatosSensiblesNiAgregaSaldosEntreCargos() throws Exception {
        charge(ownAppointmentId, dentistId, "100");
        charge(ownAppointmentId, dentistId, "250.50");
        JsonNode body = json(readContext(ownAppointmentId, owner).andExpect(status().isOk()));
        assertThat(body.has("totalAmount")).isFalse();
        assertThat(body.has("appliedAmount")).isFalse();
        assertThat(body.has("pendingBalance")).isFalse();
        assertThat(body.get("charges").get(0).has("patient")).isFalse();
        assertThat(body.toString()).doesNotContain("passwordHash", "email", "350.50");
    }

    @Test
    void losFormatosInvalidosSon400YUnaCitaInexistenteEs404() throws Exception {
        read("/api/appointments/0/financial-context", owner).andExpect(status().isBadRequest());
        read("/api/appointments/abc/financial-context", owner).andExpect(status().isBadRequest());
        read("/api/appointments/-1/financial-context", owner).andExpect(status().isBadRequest());
        readContext(Long.MAX_VALUE, owner).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Regresion de los permisos financieros actuales
    // ------------------------------------------------------------------

    @Test
    void losPermisosFinancierosActualesNoCambian() throws Exception {
        Long id = charge(ownAppointmentId, dentistId, "100").getId();
        ObjectNode body = mapper.createObjectNode().put("patientId", patientId)
                .put("appointmentId", ownAppointmentId);
        body.putArray("items").addObject().put("description", "Concepto").put("quantity", 1)
                .put("unitPrice", new BigDecimal("10"));
        // El DENTIST sigue sin escribir finanzas.
        mvc.perform(post("/api/charges").header("Authorization", "Bearer " + token(dentist))
                .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/charges/" + id + "/payments").header("Authorization", "Bearer " + token(dentist))
                .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"CASH\",\"amount\":10}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/charges/" + id + "/cancel").header("Authorization", "Bearer " + token(dentist))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Error\"}"))
                .andExpect(status().isForbidden());
        // El PATIENT sigue sin leer cargos.
        read("/api/charges", patientActor).andExpect(status().isForbidden());
        read("/api/charges/" + id, patientActor).andExpect(status().isForbidden());
        // La RECEPTION no revierte ni anula.
        mvc.perform(post("/api/charges/" + id + "/cancel").header("Authorization", "Bearer " + token(reception))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Error\"}"))
                .andExpect(status().isForbidden());
    }

    private JsonNode chargeOf(JsonNode body, Long chargeId) {
        for (JsonNode cargo : body.get("charges")) {
            if (cargo.get("id").asLong() == chargeId) {
                return cargo;
            }
        }
        throw new AssertionError("El cargo " + chargeId + " no aparece en el contexto.");
    }

    private String statusOf(JsonNode body, Long chargeId) {
        return chargeOf(body, chargeId).get("status").asText();
    }
}

package com.dentalclinic.financial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.dentalclinic.financial.FinancialOperationsIntegrationSupport;
import com.dentalclinic.financial.application.command.CancelChargeCommand;
import com.dentalclinic.financial.application.command.ConfirmChargeCommand;
import com.dentalclinic.financial.application.command.RegisterPaymentCommand;
import com.dentalclinic.financial.application.command.ReversePaymentCommand;
import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.FinancialConflictException;
import com.dentalclinic.financial.domain.FinancialIntentClosedException;
import com.dentalclinic.financial.domain.FinancialIntentCommandConflictException;
import com.dentalclinic.financial.domain.FinancialIntentCommandUnavailableException;
import com.dentalclinic.financial.domain.FinancialIntentOperation;
import com.dentalclinic.financial.domain.FinancialMovement;
import com.dentalclinic.financial.domain.InvalidChargeException;
import com.dentalclinic.financial.domain.PaymentMethod;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/** B2 real: DDL V001-V021, servicios proxificados y PostgreSQL 16 efimero, nunca :5434. */
class FinancialIntentExecutionIntegrationTest extends FinancialOperationsIntegrationSupport {
    @Autowired private FinancialIntentRegistrationService registration;
    @Autowired private FinancialIntentExecutionService execution;
    @MockitoSpyBean private FinancialIdempotencyStore idempotency;

    @ParameterizedTest
    @EnumSource(FinancialIntentOperation.class)
    void ejecutaComandoRegistradoYReplayConservaSnapshot(FinancialIntentOperation operation) {
        var c = prepare(operation, key());
        register(c, owner);
        var before = effects();
        Object first = execute(c, owner);
        assertThat(execute(c, owner)).usingRecursiveComparison().isEqualTo(first);
        assertThat(legacy(c, owner)).usingRecursiveComparison().isEqualTo(first);
        assertOneReceipt(c);
        assertSingleEffect(c, before);
        assertOpen(c);
        assertThat(jdbc.queryForObject("SELECT command_body::text FROM financial_intent WHERE idempotency_key = ?",
                String.class, c.key())).doesNotContain("snapshotVersion", "result");
    }

    @ParameterizedTest
    @EnumSource(FinancialIntentOperation.class)
    void dosEjecucionesConcurrentesTienenUnSoloEfecto(FinancialIntentOperation operation) throws Exception {
        var c = prepare(operation, key());
        register(c, owner);
        var before = effects();
        assertSameResult(concurrent(List.of(() -> execute(c, owner), () -> execute(c, owner))));
        assertOneReceipt(c);
        assertSingleEffect(c, before);
    }

    @ParameterizedTest
    @EnumSource(FinancialIntentOperation.class)
    void ejecucionConcurrenteConEndpointAntiguoComparteIdentidad(FinancialIntentOperation operation) throws Exception {
        var c = prepare(operation, key());
        register(c, owner);
        var before = effects();
        assertSameResult(concurrent(List.of(() -> execute(c, owner), () -> legacy(c, owner))));
        assertOneReceipt(c);
        assertSingleEffect(c, before);
    }

    @ParameterizedTest
    @EnumSource(FinancialIntentOperation.class)
    void payloadLegacyIncompatibleConcurrenteNoCreaEfectoAdicional(FinancialIntentOperation operation) throws Exception {
        var c = prepare(operation, key());
        register(c, owner);
        var before = effects();
        var results = concurrent(List.of(() -> execute(c, owner), () -> {
            try { return legacy(changedCommand(c), owner); }
            catch (FinancialIntentCommandConflictException conflict) { return conflict; }
        }));
        assertThat(results.get(1)).isInstanceOf(FinancialIntentCommandConflictException.class);
        assertOneReceipt(c);
        assertSingleEffect(c, before);
    }

    @Test
    void mismaClaveDeActoresDistintosConservaIntencionesYEfectosIndependientes() {
        var c = prepare(FinancialIntentOperation.FIN_CONFIRM_CHARGE, key());
        register(c, owner);
        register(c, reception);
        var first = (Charge) execute(c, owner);
        var second = (Charge) execute(c, reception);
        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(count("charge")).isEqualTo(2);
        assertThat(count("financial_intent")).isEqualTo(2);
        assertThat(count("http_idempotency")).isEqualTo(2);
    }

    @Test
    void clavesDistintasNoDeduplicanPorPacienteConceptoOImporte() {
        var first = prepare(FinancialIntentOperation.FIN_CONFIRM_CHARGE, key());
        var second = prepare(FinancialIntentOperation.FIN_CONFIRM_CHARGE, key());
        register(first, owner);
        register(second, owner);
        assertThat(((Charge) execute(first, owner)).getId()).isNotEqualTo(((Charge) execute(second, owner)).getId());
        assertThat(count("charge")).isEqualTo(2);
        assertOneReceipt(first);
        assertOneReceipt(second);
    }

    @ParameterizedTest
    @EnumSource(FinancialIntentOperation.class)
    void reciboAntiguoPuedeRecuperarseTrasRegistroSinOtroEfecto(FinancialIntentOperation operation) {
        var c = prepare(operation, key());
        Object original = legacy(c, owner);
        assertThat(count("financial_intent")).isZero();
        register(c, owner);
        var before = ledger();
        assertThat(execute(c, owner)).usingRecursiveComparison().isEqualTo(original);
        assertThat(ledger()).isEqualTo(before);
        assertOneReceipt(c);
    }

    @ParameterizedTest
    @EnumSource(FinancialIntentOperation.class)
    void contratoLegacyConClaveNoUuidNoExigeRegistro(FinancialIntentOperation operation) {
        var c = prepare(operation, "clave historica no UUID");
        Object original = legacy(c, owner);
        assertThat(legacy(c, owner)).usingRecursiveComparison().isEqualTo(original);
        assertThat(count("financial_intent")).isZero();
        assertOneReceipt(c);
    }

    @ParameterizedTest
    @MethodSource("closures")
    void cierreSinEfectoBloqueaAmbosCaminos(FinancialIntentOperation operation, String disposition) {
        var c = prepare(operation, key());
        register(c, owner);
        // Fixture SQL exclusivamente: NO se implementa transicion de B3.
        String rejection = disposition.equals("REJECTED")
                ? ", rejected_attempt_at = registered_at, rejection_code = 'FIXTURE', rejection_http_status = 409" : "";
        jdbc.update("UPDATE financial_intent SET disposition = ?, closed_at = registered_at" + rejection,
                disposition);
        var before = ledger();
        assertThatThrownBy(() -> execute(c, owner)).isInstanceOf(FinancialIntentClosedException.class);
        assertThatThrownBy(() -> legacy(c, owner)).isInstanceOf(FinancialIntentClosedException.class);
        assertThat(ledger()).isEqualTo(before);
        assertNoReceipt(c);
    }

    @ParameterizedTest
    @EnumSource(FinancialIntentOperation.class)
    void payloadAntiguoDiferenteNoPuedeEvitarElComandoRegistrado(FinancialIntentOperation operation) {
        var c = prepare(operation, key());
        register(c, owner);
        var different = changedCommand(c);
        var before = ledger();
        assertThatThrownBy(() -> legacy(different, owner)).isInstanceOf(FinancialIntentCommandConflictException.class);
        assertThat(ledger()).isEqualTo(before);
        execute(c, owner);
        assertOneReceipt(c);
    }

    @ParameterizedTest
    @EnumSource(FinancialIntentOperation.class)
    void otroActorNoRecuperaLaIntencionAunqueConozcaElUuid(FinancialIntentOperation operation) {
        var c = prepare(operation, key());
        register(c, owner);
        var before = ledger();
        Class<? extends Exception> expected = switch (operation) {
            case FIN_CONFIRM_CHARGE, FIN_REGISTER_PAYMENT -> NotFoundException.class;
            case FIN_REVERSE_PAYMENT, FIN_CANCEL_CHARGE -> ForbiddenOperationException.class;
        };
        assertThatThrownBy(() -> execute(c, reception)).isInstanceOf(expected);
        assertThatThrownBy(() -> execute(c, null)).isInstanceOf(ForbiddenOperationException.class);
        assertThat(ledger()).isEqualTo(before);
    }

    @Test
    void ejecutarRequiereRegistroNoBastaConUnaClaveValida() {
        var c = prepare(FinancialIntentOperation.FIN_REGISTER_PAYMENT, key());
        var before = ledger();
        assertThatThrownBy(() -> execute(c, owner)).isInstanceOf(NotFoundException.class);
        assertThat(ledger()).isEqualTo(before);
        assertThat(count("financial_intent")).isZero();
    }

    @Test
    void permisosSeRevalidanAlEjecutarYEnReplayConfirmado() {
        var c = prepare(FinancialIntentOperation.FIN_REGISTER_PAYMENT, key());
        register(c, reception);
        var denied = new AuthenticatedUser(reception.userId(), Role.DENTIST);
        jdbc.update("UPDATE users SET role = 'DENTIST' WHERE id = ?", reception.userId());
        assertThatThrownBy(() -> execute(c, denied)).isInstanceOf(ForbiddenOperationException.class);
        jdbc.update("UPDATE users SET role = 'RECEPTIONIST' WHERE id = ?", reception.userId());
        execute(c, reception);
        var before = ledger();
        jdbc.update("UPDATE users SET role = 'DENTIST' WHERE id = ?", reception.userId());
        assertThatThrownBy(() -> execute(c, denied)).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> legacy(c, denied)).isInstanceOf(ForbiddenOperationException.class);
        assertThat(ledger()).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(FinancialIntentOperation.class)
    void reglasDeNegocioSeRevalidanYB3SoloCierraRechazosTipificados(FinancialIntentOperation operation) {
        var c = prepare(operation, key());
        register(c, owner);
        switch (operation) {
            case FIN_CONFIRM_CHARGE -> jdbc.update("UPDATE appointment SET patient_id = ? WHERE id = ?",
                    otherPatientId, ownAppointmentId);
            case FIN_REGISTER_PAYMENT, FIN_CANCEL_CHARGE -> operations.registerPayment(new RegisterPaymentCommand(
                    c.chargeId(), PaymentMethod.CASH, new BigDecimal("40"), null, key()), owner);
            case FIN_REVERSE_PAYMENT -> {
                var command = (ReversePaymentCommand) c.command();
                operations.reversePayment(new ReversePaymentCommand(command.chargeId(), command.movementId(), "otra reversa", key()), owner);
            }
        }
        var before = ledger();
        assertThatThrownBy(() -> execute(c, owner)).isInstanceOfAny(FinancialConflictException.class, InvalidChargeException.class);
        assertThat(ledger().subList(0, 4)).isEqualTo(before.subList(0, 4));
        assertNoReceipt(c);
        if (operation == FinancialIntentOperation.FIN_CONFIRM_CHARGE) {
            assertOpen(c); // Referencia incompatible no pertenece a la allowlist de cierre B3.
        } else {
            assertThat(jdbc.queryForObject("SELECT disposition FROM financial_intent WHERE idempotency_key = ?", String.class, c.key())).isEqualTo("REJECTED");
        }
    }

    @ParameterizedTest
    @MethodSource("receiptFailures")
    void falloAntesODespuesDelReciboRevierteEfectoYPermiteReintento(FinancialIntentOperation operation, boolean afterReceipt) {
        var c = prepare(operation, key());
        register(c, owner);
        var before = ledger();
        FinancialIdempotencyStore target = AopTestUtils.getUltimateTargetObject(idempotency);
        doAnswer(invocation -> {
            if (afterReceipt) invocation.callRealMethod(); // Flush real del efecto Y recibo antes del fallo.
            throw new QueryTimeoutException("fallo tecnico inyectado");
        }).when(target).remember(eq(owner), eq(operation.name()), eq(c.key()), any(), anyInt(), any(), any());

        assertThatThrownBy(() -> execute(c, owner)).isInstanceOf(QueryTimeoutException.class);
        assertThat(ledger()).isEqualTo(before);
        assertNoReceipt(c);
        assertOpen(c);
        reset(target);
        Object result = execute(c, owner);
        assertThat(execute(c, owner)).usingRecursiveComparison().isEqualTo(result);
        assertOneReceipt(c);
    }

    @Test
    void commitRealConRespuestaPerdidaSeRecuperaAunqueElCargoHayaCambiado() {
        var c = prepare(FinancialIntentOperation.FIN_REGISTER_PAYMENT, key());
        register(c, owner);
        FinancialMovement original = (FinancialMovement) execute(c, owner); // Commit real; transporte pierde su respuesta.
        operations.reversePayment(new ReversePaymentCommand(c.chargeId(), original.getId(), "correccion posterior", key()), owner);
        operations.cancelCharge(new CancelChargeCommand(c.chargeId(), "correccion posterior", key()), owner);
        var before = ledger();
        assertThat(execute(c, owner)).usingRecursiveComparison().isEqualTo(original);
        assertThat(legacy(c, owner)).usingRecursiveComparison().isEqualTo(original);
        assertThat(ledger()).isEqualTo(before);
    }

    @Test
    void comandoDurableAlteradoNiVersionIncompatibleProducenEfectos() {
        for (String alteration : List.of("command_version = 3", "hash_version = 2",
                "command_body = jsonb_set(command_body, '{amount}', '11'::jsonb)",
                "command_body = jsonb_set(command_body, '{idempotencyKey}', '\"" + key() + "\"'::jsonb)")) {
            var c = prepare(FinancialIntentOperation.FIN_REGISTER_PAYMENT, key());
            register(c, owner);
            jdbc.update("UPDATE financial_intent SET " + alteration + " WHERE idempotency_key = ?", c.key());
            var before = ledger();
            assertThatThrownBy(() -> execute(c, owner)).isInstanceOf(FinancialIntentCommandConflictException.class);
            assertThat(ledger()).isEqualTo(before);
            assertNoReceipt(c);
        }
    }

    @Test
    void snapshotIncompatibleEsFalloTecnicoNuncaRechazoDurable() {
        var c = prepare(FinancialIntentOperation.FIN_REGISTER_PAYMENT, key());
        register(c, owner);
        execute(c, owner);
        jdbc.update("UPDATE http_idempotency SET response_body = '{\"snapshotVersion\":2}'::jsonb WHERE idempotency_key = ?", c.key());
        var before = ledger();
        assertThatThrownBy(() -> execute(c, owner)).isInstanceOf(IllegalStateException.class);
        assertThat(ledger()).isEqualTo(before);
        assertOpen(c);
    }

    @Test
    void importeExponencialValidoDebeConservarCompatibilidadAlRecuperarComandoDurable() {
        Charge charge = confirmed("100");
        var command = new RegisterPaymentCommand(charge.getId(), PaymentMethod.CASH,
                new BigDecimal("1E+2"), "importe valido anterior", key());
        var c = new Case(FinancialIntentOperation.FIN_REGISTER_PAYMENT, command.idempotencyKey(), command, charge.getId());
        register(c, owner);
        // El escritor existente acepta el importe y genera un recibo confirmado con su hash original.
        FinancialMovement original = (FinancialMovement) legacy(c, owner);
        assertThat(original.getAmount()).isEqualByComparingTo("100");
        // AD-34 exige recuperar tambien esta respuesta sin cambiar el criterio de hash existente.
        assertThat(execute(c, owner)).usingRecursiveComparison().isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1E+2", "1.00E+2", "100.00", "1.2300", "0.0100", "1.234E+4", "1E+3", "999999999999999.99"})
    void codecV2ConservaRepresentacionYHashAntesDeEjecutar(String amount) throws Exception {
        Charge charge = confirmed("999999999999999.99");
        var command = new RegisterPaymentCommand(charge.getId(), PaymentMethod.CARD, new BigDecimal(amount), "nota", key());
        var c = new Case(FinancialIntentOperation.FIN_REGISTER_PAYMENT, command.idempotencyKey(), command, charge.getId());
        register(c, owner);
        String body = jdbc.queryForObject("SELECT command_body::text FROM financial_intent WHERE idempotency_key = ?", String.class, c.key());
        var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        assertThat(tree.get("amount").isTextual()).isTrue();
        assertThat(tree.get("amount").textValue()).isEqualTo(command.amount().toString());
        var restored = transaction().execute(status -> idempotency.readCommand(body, RegisterPaymentCommand.class));
        assertThat(restored).isEqualTo(command); // BigDecimal.equals compara tambien scale.
        assertThat(restored.amount().unscaledValue()).isEqualTo(command.amount().unscaledValue());
        assertThat(restored.amount().scale()).isEqualTo(command.amount().scale());
        String hash = transaction().execute(status -> idempotency.requestHash(restored));
        assertThat(jdbc.queryForObject("SELECT request_hash FROM financial_intent WHERE idempotency_key = ?", String.class, c.key())).isEqualTo(hash);
        assertThat(jdbc.queryForObject("SELECT command_version FROM financial_intent WHERE idempotency_key = ?", Integer.class, c.key())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT hash_version FROM financial_intent WHERE idempotency_key = ?", Integer.class, c.key())).isEqualTo(1);
        var before = effects();
        Object original = execute(c, owner); // No recibo previo: prueba ejecucion, no solo fallback/replay.
        assertThat(legacy(c, owner)).usingRecursiveComparison().isEqualTo(original);
        assertThat(execute(c, owner)).usingRecursiveComparison().isEqualTo(original);
        assertSingleEffect(c, before);
        assertOneReceipt(c);
    }

    @Test
    void codecV2PreservaDecimalesAnidadosYCeroConExponenteEnConceptos() {
        var command = new ConfirmChargeCommand(patientId, ownAppointmentId, List.of(
                new com.dentalclinic.financial.application.command.ChargeItemData(null, dentistId, "Cero", 1, new BigDecimal("0E+2")),
                new com.dentalclinic.financial.application.command.ChargeItemData(null, dentistId, "Servicio", 1, new BigDecimal("1E+2"))), "nota", key());
        var c = new Case(FinancialIntentOperation.FIN_CONFIRM_CHARGE, command.idempotencyKey(), command, null);
        register(c, owner);
        var first = (Charge) execute(c, owner);
        assertThat(first.getTotalAmount()).isEqualByComparingTo("100");
        assertThat(legacy(c, owner)).usingRecursiveComparison().isEqualTo(first);
        assertThat(execute(c, owner)).usingRecursiveComparison().isEqualTo(first);
        assertThat(count("charge")).isEqualTo(1);
        assertThat(count("charge_item")).isEqualTo(2);
        assertOneReceipt(c);
    }

    @ParameterizedTest
    @ValueSource(strings = {"100.00", "1.2300", "0.0100"})
    void comandoB1RecuperableSeEjecutaSinReescribirVersionOCuerpo(String amount) {
        Charge charge = confirmed("100");
        var command = new RegisterPaymentCommand(charge.getId(), PaymentMethod.CASH, new BigDecimal(amount), null, key());
        var c = new Case(FinancialIntentOperation.FIN_REGISTER_PAYMENT, command.idempotencyKey(), command, charge.getId());
        insertB1(c);
        String originalIntent = intentRow(c);
        assertThat(registration.register(command, owner).commandVersion()).isEqualTo(1);
        assertThat(intentRow(c)).isEqualTo(originalIntent);
        var before = effects();
        Object result = execute(c, owner);
        assertThat(execute(c, owner)).usingRecursiveComparison().isEqualTo(result);
        assertThat(intentRow(c)).isEqualTo(originalIntent);
        assertSingleEffect(c, before);
        assertOneReceipt(c);
    }

    @Test
    void comandoB1PerdidoSinReciboNoSeInfierePeroOriginalLegacyPuedeResolverLaMismaIdentidad() {
        Charge charge = confirmed("100");
        var command = new RegisterPaymentCommand(charge.getId(), PaymentMethod.CASH, new BigDecimal("1E+2"), null, key());
        var c = new Case(FinancialIntentOperation.FIN_REGISTER_PAYMENT, command.idempotencyKey(), command, charge.getId());
        insertB1(c);
        String originalIntent = intentRow(c);
        assertThat(registration.register(command, owner).commandVersion()).isEqualTo(1);
        var before = ledger();
        assertThatThrownBy(() -> execute(c, owner)).isInstanceOf(FinancialIntentCommandUnavailableException.class);
        assertThat(ledger()).isEqualTo(before);
        assertNoReceipt(c);
        assertOpen(c);
        Object result = legacy(c, owner); // El llamador aun conserva el comando original, con el hash exacto.
        before = ledger();
        assertThat(execute(c, owner)).usingRecursiveComparison().isEqualTo(result);
        assertThat(ledger()).isEqualTo(before);
        assertThat(intentRow(c)).isEqualTo(originalIntent);
        assertOneReceipt(c);
        assertThat(count("financial_movement")).isEqualTo(1);
    }

    @Test
    void reciboB1ConfirmadoRecuperaRespuestaAunqueElJsonNumericoHayaPerdidoRepresentacion() {
        Charge charge = confirmed("100");
        var command = new RegisterPaymentCommand(charge.getId(), PaymentMethod.CARD, new BigDecimal("1E+2"), null, key());
        var c = new Case(FinancialIntentOperation.FIN_REGISTER_PAYMENT, command.idempotencyKey(), command, charge.getId());
        insertB1(c);
        Object original = legacy(c, owner);
        operations.reversePayment(new ReversePaymentCommand(charge.getId(), ((FinancialMovement) original).getId(), "correccion", key()), owner);
        operations.cancelCharge(new CancelChargeCommand(charge.getId(), "correccion", key()), owner);
        var before = ledger();
        FinancialIdempotencyStore target = AopTestUtils.getUltimateTargetObject(idempotency);
        org.mockito.Mockito.doThrow(new AssertionError("No reconstruir comando con recibo confirmado"))
                .when(target).readCommand(org.mockito.ArgumentMatchers.anyString(), any());
        assertThat(execute(c, owner)).usingRecursiveComparison().isEqualTo(original);
        assertThat(ledger()).isEqualTo(before);
        assertOneReceipt(c);
    }

    @Test
    void reciboB1NoEvitaRevalidarPermisosDeSuActor() {
        Charge charge = confirmed("100");
        var command = new RegisterPaymentCommand(charge.getId(), PaymentMethod.CARD, new BigDecimal("1E+2"), null, key());
        var c = new Case(FinancialIntentOperation.FIN_REGISTER_PAYMENT, command.idempotencyKey(), command, charge.getId());
        insertB1(c);
        legacy(c, owner);
        var before = ledger();
        assertThatThrownBy(() -> execute(c, new AuthenticatedUser(owner.userId(), Role.DENTIST))).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> execute(c, reception)).isInstanceOf(NotFoundException.class);
        assertThat(ledger()).isEqualTo(before);
    }

    @Test
    void comandoB1DeCargoConExponenteYReciboSeRecuperaSinOtroCargo() {
        var command = new ConfirmChargeCommand(patientId, ownAppointmentId,
                List.of(new com.dentalclinic.financial.application.command.ChargeItemData(null, dentistId, "Servicio", 1, new BigDecimal("1E+2"))), null, key());
        var c = new Case(FinancialIntentOperation.FIN_CONFIRM_CHARGE, command.idempotencyKey(), command, null);
        insertB1(c);
        var before = ledger();
        assertThatThrownBy(() -> execute(c, owner)).isInstanceOf(FinancialIntentCommandUnavailableException.class);
        assertThat(ledger()).isEqualTo(before);
        var original = legacy(c, owner);
        before = ledger();
        assertThat(execute(c, owner)).usingRecursiveComparison().isEqualTo(original);
        assertThat(ledger()).isEqualTo(before);
        assertThat(count("charge")).isEqualTo(1);
        assertOneReceipt(c);
    }

    @Test
    void reciboIncompatibleONuloNoSeInterpretaComoPermisoDeEjecutar() {
        for (String badResult : List.of("'{\"snapshotVersion\":1,\"result\":null}'::jsonb", "'{\"snapshotVersion\":2}'::jsonb")) {
            var c = prepare(FinancialIntentOperation.FIN_REGISTER_PAYMENT, key());
            register(c, owner);
            execute(c, owner);
            jdbc.update("UPDATE http_idempotency SET response_body = " + badResult + " WHERE idempotency_key = ?", c.key());
            var before = ledger();
            assertThatThrownBy(() -> execute(c, owner)).isInstanceOf(IllegalStateException.class);
            assertThat(ledger()).isEqualTo(before);
            assertOpen(c);
        }
    }

    private void insertB1(Case c) {
        transaction().execute(status -> {
            String hash = idempotency.lockRequest(owner, FinancialIdempotencyStore.FINANCIAL_NAMESPACE,
                    c.operation().name(), c.key(), c.command());
            // Reproduce EXACTAMENTE el registro B1/V021 anterior; JSONB recibe numeros, no strings.
            jdbc.update("INSERT INTO financial_intent (actor_user_id, operation, idempotency_key, command_version, hash_version, request_hash, command_body) "
                    + "VALUES (?, ?, ?, 1, 1, ?, CAST(? AS jsonb))", owner.userId(), c.operation().name(), c.key(), hash,
                    idempotency.commandJson(c.command()));
            return null;
        });
    }

    private String intentRow(Case c) {
        return jdbc.queryForObject("SELECT to_jsonb(t)::text FROM financial_intent t WHERE idempotency_key = ?", String.class, c.key());
    }

    @Test
    void commitEsIndependienteDelRollbackOReadOnlyDelLlamador() {
        var c = prepare(FinancialIntentOperation.FIN_REGISTER_PAYMENT, key());
        register(c, owner);
        TransactionTemplate outer = transaction();
        outer.setReadOnly(true);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.execute(status -> {
            execute(c, owner);
            status.setRollbackOnly();
            return null;
        });
        assertOneReceipt(c);
        assertThat(count("financial_movement")).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("lockOrders")
    void esperaFilaDeIntencionAntesDeBloquearCitaOCargo(FinancialIntentOperation operation, boolean legacy) throws Exception {
        var c = prepare(operation, key());
        register(c, owner);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder = pool.submit(() -> transaction().execute(status -> {
                jdbc.queryForObject("SELECT actor_user_id FROM financial_intent WHERE idempotency_key = ? FOR UPDATE", Long.class, c.key());
                locked.countDown();
                await(release);
                return null;
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var executing = pool.submit(() -> legacy ? legacy(c, owner) : execute(c, owner));
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    waiting = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname = current_database() "
                            + "AND wait_event_type = 'Lock' AND query LIKE 'SELECT * FROM financial_intent%')", Boolean.class);
                    if (waiting) break;
                    Thread.onSpinWait();
                }
                assertThat(waiting).isTrue();
                boolean confirm = operation == FinancialIntentOperation.FIN_CONFIRM_CHARGE;
                Long businessId = confirm ? ownAppointmentId : c.chargeId();
                String table = confirm ? "appointment" : "charge";
                assertThat(jdbc.queryForObject("SELECT id FROM " + table + " WHERE id = ? FOR UPDATE NOWAIT", Long.class, businessId))
                        .isEqualTo(businessId);
                assertThat(executing.isDone()).isFalse();
                release.countDown();
                executing.get(15, TimeUnit.SECONDS);
            } finally {
                release.countDown();
                holder.get(15, TimeUnit.SECONDS);
            }
        }
        assertOneReceipt(c);
    }

    private Case prepare(FinancialIntentOperation operation, String key) {
        if (operation == FinancialIntentOperation.FIN_CONFIRM_CHARGE) {
            return new Case(operation, key, command(ownAppointmentId, dentistId, "100", key), null);
        }
        Charge charge = confirmed("100");
        Object command = switch (operation) {
            case FIN_REGISTER_PAYMENT -> new RegisterPaymentCommand(charge.getId(), PaymentMethod.CARD, new BigDecimal("100.00"), "nota", key);
            case FIN_REVERSE_PAYMENT -> new ReversePaymentCommand(charge.getId(), payment(charge, "100", key()).getId(), "motivo", key);
            case FIN_CANCEL_CHARGE -> new CancelChargeCommand(charge.getId(), "motivo", key);
            default -> throw new IllegalArgumentException();
        };
        return new Case(operation, key, command, charge.getId());
    }

    private Case changedCommand(Case c) {
        Object command = switch (c.operation()) {
            case FIN_CONFIRM_CHARGE -> command(ownAppointmentId, dentistId, "101", c.key());
            case FIN_REGISTER_PAYMENT -> new RegisterPaymentCommand(c.chargeId(), PaymentMethod.CASH, BigDecimal.ONE, "otra", c.key());
            case FIN_REVERSE_PAYMENT -> {
                var original = (ReversePaymentCommand) c.command();
                yield new ReversePaymentCommand(c.chargeId(), original.movementId(), "otra", c.key());
            }
            case FIN_CANCEL_CHARGE -> new CancelChargeCommand(c.chargeId(), "otra", c.key());
        };
        return new Case(c.operation(), c.key(), command, c.chargeId());
    }

    private void register(Case c, AuthenticatedUser actor) {
        switch (c.operation()) {
            case FIN_CONFIRM_CHARGE -> registration.register((ConfirmChargeCommand) c.command(), actor);
            case FIN_REGISTER_PAYMENT -> registration.register((RegisterPaymentCommand) c.command(), actor);
            case FIN_REVERSE_PAYMENT -> registration.register((ReversePaymentCommand) c.command(), actor);
            case FIN_CANCEL_CHARGE -> registration.register((CancelChargeCommand) c.command(), actor);
        }
    }

    private Object execute(Case c, AuthenticatedUser actor) {
        return switch (c.operation()) {
            case FIN_CONFIRM_CHARGE -> execution.confirmCharge(c.key(), actor);
            case FIN_REGISTER_PAYMENT -> execution.registerPayment(c.key(), actor);
            case FIN_REVERSE_PAYMENT -> execution.reversePayment(c.key(), actor);
            case FIN_CANCEL_CHARGE -> execution.cancelCharge(c.key(), actor);
        };
    }

    private Object legacy(Case c, AuthenticatedUser actor) {
        return switch (c.operation()) {
            case FIN_CONFIRM_CHARGE -> operations.confirmCharge((ConfirmChargeCommand) c.command(), actor);
            case FIN_REGISTER_PAYMENT -> operations.registerPayment((RegisterPaymentCommand) c.command(), actor);
            case FIN_REVERSE_PAYMENT -> operations.reversePayment((ReversePaymentCommand) c.command(), actor);
            case FIN_CANCEL_CHARGE -> operations.cancelCharge((CancelChargeCommand) c.command(), actor);
        };
    }

    private void assertOneReceipt(Case c) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE actor_user_id = ? AND operation = ? AND idempotency_key = ?",
                Long.class, owner.userId(), c.operation().name(), c.key())).isEqualTo(1);
    }

    private void assertNoReceipt(Case c) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE operation = ? AND idempotency_key = ?",
                Long.class, c.operation().name(), c.key())).isZero();
    }

    private void assertOpen(Case c) {
        assertThat(jdbc.queryForObject("SELECT disposition FROM financial_intent WHERE idempotency_key = ?", String.class, c.key())).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT closed_at IS NULL AND rejection_code IS NULL FROM financial_intent WHERE idempotency_key = ?",
                Boolean.class, c.key())).isTrue();
    }

    private Effects effects() {
        return new Effects(count("charge"), count("charge_item"), count("financial_movement"),
                jdbc.queryForObject("SELECT count(*) FROM charge WHERE cancelled_at IS NOT NULL", Long.class));
    }

    private void assertSingleEffect(Case c, Effects before) {
        var after = effects();
        boolean confirm = c.operation() == FinancialIntentOperation.FIN_CONFIRM_CHARGE;
        boolean movement = c.operation() == FinancialIntentOperation.FIN_REGISTER_PAYMENT
                || c.operation() == FinancialIntentOperation.FIN_REVERSE_PAYMENT;
        boolean cancel = c.operation() == FinancialIntentOperation.FIN_CANCEL_CHARGE;
        assertThat(after.charges() - before.charges()).isEqualTo(confirm ? 1 : 0);
        assertThat(after.items() - before.items()).isEqualTo(confirm ? 1 : 0);
        assertThat(after.movements() - before.movements()).isEqualTo(movement ? 1 : 0);
        assertThat(after.cancelled() - before.cancelled()).isEqualTo(cancel ? 1 : 0);
    }

    private List<List<String>> ledger() {
        return Stream.of("charge", "charge_item", "financial_movement", "http_idempotency", "financial_intent")
                .map(table -> jdbc.queryForList("SELECT to_jsonb(t)::text FROM " + table + " t ORDER BY to_jsonb(t)::text", String.class)).toList();
    }

    private static void assertSameResult(List<Object> results) {
        assertThat(results.get(0)).usingRecursiveComparison().isEqualTo(results.get(1));
    }

    private static List<Object> concurrent(List<Callable<Object>> calls) throws Exception {
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(calls.size())) {
            var futures = calls.stream().map(call -> pool.submit(() -> {
                ready.countDown();
                await(start);
                return call.call();
            })).toList();
            try { assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { start.countDown(); }
            List<Object> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("timeout de prueba");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private TransactionTemplate transaction() {
        var tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx;
    }

    static Stream<Arguments> closures() {
        return Stream.of(FinancialIntentOperation.values()).flatMap(op -> Stream.of("REJECTED", "ABANDONED").map(s -> Arguments.of(op, s)));
    }

    static Stream<Arguments> receiptFailures() {
        return Stream.of(FinancialIntentOperation.values()).flatMap(op -> Stream.of(false, true).map(after -> Arguments.of(op, after)));
    }

    static Stream<Arguments> lockOrders() {
        return Stream.of(FinancialIntentOperation.FIN_CONFIRM_CHARGE, FinancialIntentOperation.FIN_REGISTER_PAYMENT)
                .flatMap(op -> Stream.of(false, true).map(legacy -> Arguments.of(op, legacy)));
    }

    private record Effects(long charges, long items, long movements, long cancelled) { }
    private record Case(FinancialIntentOperation operation, String key, Object command, Long chargeId) { }
}

package com.dentalclinic.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import com.dentalclinic.financial.application.command.CancelChargeCommand;
import com.dentalclinic.financial.application.command.ChargeItemData;
import com.dentalclinic.financial.application.command.ConfirmChargeCommand;
import com.dentalclinic.financial.application.command.RegisterPaymentCommand;
import com.dentalclinic.financial.application.command.ReversePaymentCommand;
import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.ChargeStatus;
import com.dentalclinic.financial.domain.FinancialConflictException;
import com.dentalclinic.financial.domain.FinancialMovement;
import com.dentalclinic.financial.domain.FinancialMovementType;
import com.dentalclinic.financial.domain.InvalidChargeException;
import com.dentalclinic.financial.domain.InvalidFinancialMovementException;
import com.dentalclinic.financial.domain.PaymentMethod;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

class FinancialOperationsIntegrationTest extends FinancialOperationsIntegrationSupport {
    enum Write { CONFIRM, PAYMENT, REVERSE, CANCEL }

    @Test
    void confirmacionManualCalculaTotalConservaSnapshotYNoAlteraCita() {
        long appointments = count("appointment");
        ConfirmChargeCommand command = new ConfirmChargeCommand(patientId, ownAppointmentId,
                List.of(item(dentistId, "Descripcion historica", 3, "250.50"),
                        item(null, "Concepto adicional", 1, "48.50")), "Acuerdo", key());

        Charge charge = operations.confirmCharge(command, reception);

        assertThat(charge.getTotalAmount()).isEqualByComparingTo("800.00");
        assertThat(charge.getCurrency()).isEqualTo("MXN");
        assertThat(charge.getConfirmedByUserId()).isEqualTo(reception.userId());
        assertThat(charge.getConfirmedAt()).isNotNull();
        assertThat(charge.getCreatedAt()).isNotNull();
        assertThat(count("charge_item")).isEqualTo(2);
        assertThat(count("financial_movement")).isZero();
        assertThat(count("appointment")).isEqualTo(appointments);
        assertThat(jdbc.queryForObject("SELECT status FROM appointment WHERE id = ?",
                String.class, ownAppointmentId)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT SUM(line_total) FROM charge_item WHERE charge_id = ?",
                BigDecimal.class, charge.getId())).isEqualByComparingTo(charge.getTotalAmount());
    }

    @Test
    void precioReferencialEditadoNoRecalculaConceptoHistorico() {
        Long serviceId = jdbc.queryForObject("INSERT INTO service_catalog_item "
                + "(name, reference_price, duration_minutes, active, created_at) "
                + "VALUES ('Catalogo original', 100, 60, TRUE, now()) RETURNING id", Long.class);
        ChargeItemData item = new ChargeItemData(serviceId, dentistId, "Precio acordado", 1,
                new BigDecimal("123.45"));
        Charge charge = operations.confirmCharge(new ConfirmChargeCommand(patientId, ownAppointmentId,
                List.of(item), null, key()), reception);
        jdbc.update("UPDATE service_catalog_item SET name = 'Nombre nuevo', reference_price = 999 WHERE id = ?", serviceId);

        assertThat(jdbc.queryForObject("SELECT description FROM charge_item WHERE charge_id = ?",
                String.class, charge.getId())).isEqualTo("Precio acordado");
        assertThat(jdbc.queryForObject("SELECT unit_price FROM charge_item WHERE charge_id = ?",
                BigDecimal.class, charge.getId())).isEqualByComparingTo("123.45");
    }

    @Test
    void exigePacienteConceptosYTotalPositivo() {
        for (List<ChargeItemData> concepts : java.util.Arrays.<List<ChargeItemData>>asList(null, List.of(),
                List.of(item(null, "Gratis", 1, "0")))) {
            assertThatThrownBy(() -> operations.confirmCharge(new ConfirmChargeCommand(patientId, null,
                    concepts, null, key()), owner)).isInstanceOf(InvalidChargeException.class);
        }
        assertThatThrownBy(() -> operations.confirmCharge(new ConfirmChargeCommand(null, null,
                List.of(item(null, "Consulta", 1, "10")), null, key()), owner))
                .isInstanceOf(InvalidChargeException.class);
        assertThat(count("charge")).isZero();
        assertThat(count("http_idempotency")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "0.001", "1.005"})
    void rechazaPreciosInvalidosSinRedondear(String price) {
        assertThatThrownBy(() -> operations.confirmCharge(command(null, null, price, key()), owner))
                .isInstanceOf(InvalidChargeException.class);
        assertThat(count("charge")).isZero();
    }

    @Test
    void exigeCantidadPositivaYDescripcionNoVacia() {
        for (ChargeItemData item : List.of(item(null, "Consulta", 0, "10"), item(null, "  ", 1, "10"))) {
            assertThatThrownBy(() -> operations.confirmCharge(new ConfirmChargeCommand(patientId, null,
                    List.of(item), null, key()), owner)).isInstanceOf(InvalidChargeException.class);
        }
        assertThat(count("charge")).isZero();
        assertThat(count("charge_item")).isZero();
    }

    @Test
    void validaPacienteCitaProfesionalYCatalogoPersistidos() {
        assertThatThrownBy(() -> operations.confirmCharge(new ConfirmChargeCommand(Long.MAX_VALUE, null,
                List.of(item(null, "Consulta", 1, "10")), null, key()), owner)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> operations.confirmCharge(command(Long.MAX_VALUE, null, "10", key()), owner))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> operations.confirmCharge(command(null, Long.MAX_VALUE, "10", key()), owner))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> operations.confirmCharge(new ConfirmChargeCommand(patientId, null,
                List.of(new ChargeItemData(Long.MAX_VALUE, null, "Consulta", 1, BigDecimal.TEN)), null, key()), owner))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> operations.confirmCharge(new ConfirmChargeCommand(otherPatientId, ownAppointmentId,
                List.of(item(null, "Consulta", 1, "10")), null, key()), owner)).isInstanceOf(InvalidChargeException.class);
        assertThat(count("charge")).isZero();
    }

    @Test
    void dentistSoloLeeAtencionPropiaSinPoderCrearNiCobrar() {
        Charge charge = operations.confirmCharge(command(ownAppointmentId, dentistId, "100", key()), owner);
        FinancialMovement payment = operations.registerPayment(new RegisterPaymentCommand(charge.getId(),
                PaymentMethod.TRANSFER, new BigDecimal("40"), null, key()), owner);

        assertThat(payment.getPerformedByUserId()).isEqualTo(owner.userId());
        assertThat(states.stateOf(charge.getId(), dentist).pendingBalance()).isEqualByComparingTo("60");
        assertThatThrownBy(() -> operations.confirmCharge(command(ownAppointmentId, dentistId, "100", key()), dentist))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> operations.registerPayment(new RegisterPaymentCommand(charge.getId(),
                PaymentMethod.CASH, BigDecimal.ONE, null, key()), dentist))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> states.stateOf(charge.getId(), otherDentist)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void dentistNoCreaCargoAjenoMixtoSinCitaNiConPacienteAjeno() {
        for (ConfirmChargeCommand command : List.of(command(null, dentistId, "100", key()),
                command(otherAppointmentId, dentistId, "100", key()),
                new ConfirmChargeCommand(patientId, ownAppointmentId,
                        List.of(item(dentistId, "Propio", 1, "50"), item(otherDentistId, "Ajeno", 1, "50")), null, key()),
                new ConfirmChargeCommand(otherPatientId, ownAppointmentId,
                        List.of(item(dentistId, "Propio", 1, "100")), null, key()))) {
            assertThatThrownBy(() -> operations.confirmCharge(command, dentist))
                    .isInstanceOf(ForbiddenOperationException.class);
        }
        assertThat(count("charge")).isZero();
    }

    @Test
    void dentistNoCobraCargosMixtosOSinVinculoVerificable() {
        Charge mixed = operations.confirmCharge(new ConfirmChargeCommand(patientId, ownAppointmentId,
                List.of(item(dentistId, "Propio", 1, "50"), item(otherDentistId, "Ajeno", 1, "50")), null, key()), owner);
        Charge noAppointment = operations.confirmCharge(command(null, dentistId, "100", key()), owner);
        for (Charge charge : List.of(mixed, noAppointment)) {
            assertThatThrownBy(() -> operations.registerPayment(new RegisterPaymentCommand(charge.getId(),
                    PaymentMethod.CASH, BigDecimal.ONE, null, key()), dentist))
                    .isInstanceOf(ForbiddenOperationException.class);
        }
        assertThat(count("financial_movement")).isZero();
    }

    @Test
    void patientSinAccesoYSoloOwnerCorrigeOAnula() {
        Charge charge = confirmed("100");
        FinancialMovement payment = payment(charge, "10", key());
        assertThatThrownBy(() -> operations.confirmCharge(command(null, null, "100", key()), patientActor))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> operations.registerPayment(new RegisterPaymentCommand(charge.getId(),
                PaymentMethod.CASH, BigDecimal.ONE, null, key()), patientActor)).isInstanceOf(ForbiddenOperationException.class);
        for (AuthenticatedUser actor : List.of(reception, dentist, patientActor)) {
            assertThatThrownBy(() -> operations.reversePayment(new ReversePaymentCommand(charge.getId(),
                    payment.getId(), "Error", key()), actor)).isInstanceOf(ForbiddenOperationException.class);
            assertThatThrownBy(() -> operations.cancelCharge(new CancelChargeCommand(charge.getId(), "Error", key()), actor))
                    .isInstanceOf(ForbiddenOperationException.class);
        }
    }

    @ParameterizedTest
    @EnumSource(PaymentMethod.class)
    void pagosParcialesYCompletosConservanMetodoYAutor(PaymentMethod method) {
        Charge charge = confirmed("100.30");
        FinancialMovement first = operations.registerPayment(new RegisterPaymentCommand(charge.getId(), method,
                new BigDecimal("0.10"), null, key()), reception);
        assertThat(states.stateOf(charge.getId(), owner).status()).isEqualTo(ChargeStatus.PARCIAL);
        assertThat(states.stateOf(charge.getId(), owner).pendingBalance()).isEqualByComparingTo("100.20");
        FinancialMovement second = payment(charge, "100.20", key());
        assertThat(states.stateOf(charge.getId(), owner).status()).isEqualTo(ChargeStatus.PAGADO);
        assertThat(first.getPaymentMethod()).isEqualTo(method);
        assertThat(first.getPerformedByUserId()).isEqualTo(reception.userId());
        assertThat(second.getAmount()).isEqualByComparingTo("100.20");
        assertThat(count("financial_movement")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.001"})
    void rechazaImportesDePagoInvalidos(String amount) {
        Charge charge = confirmed("100");
        assertThatThrownBy(() -> payment(charge, amount, key())).isInstanceOf(InvalidFinancialMovementException.class);
        assertThat(count("financial_movement")).isZero();
    }

    @Test
    void noAdmiteSobrepagoAnticipoMetodoNuloNiPagoEnCargoAnulado() {
        Charge charge = confirmed("100");
        assertThatThrownBy(() -> payment(charge, "100.01", key())).isInstanceOf(FinancialConflictException.class);
        assertThatThrownBy(() -> operations.registerPayment(new RegisterPaymentCommand(null, PaymentMethod.CASH,
                BigDecimal.ONE, null, key()), owner)).isInstanceOf(InvalidFinancialMovementException.class);
        assertThatThrownBy(() -> operations.registerPayment(new RegisterPaymentCommand(charge.getId(), null,
                BigDecimal.ONE, null, key()), owner)).isInstanceOf(InvalidFinancialMovementException.class);
        operations.cancelCharge(new CancelChargeCommand(charge.getId(), "Error", key()), owner);
        assertThatThrownBy(() -> payment(charge, "10", key())).isInstanceOf(FinancialConflictException.class);
        assertThat(count("financial_movement")).isZero();
    }

    @Test
    void reversaCompletaConservaOriginalReabreSaldoYRechazaDobleReversa() {
        Charge charge = confirmed("100");
        FinancialMovement original = payment(charge, "100", key());
        FinancialMovement reversal = operations.reversePayment(new ReversePaymentCommand(charge.getId(),
                original.getId(), "Duplicado", key()), owner);
        assertThat(reversal.getType()).isEqualTo(FinancialMovementType.REVERSAL);
        assertThat(reversal.getAmount()).isEqualByComparingTo(original.getAmount());
        assertThat(reversal.getPatientId()).isEqualTo(original.getPatientId());
        assertThat(reversal.getReversesMovementId()).isEqualTo(original.getId());
        assertThat(states.stateOf(charge.getId(), owner).status()).isEqualTo(ChargeStatus.PENDIENTE);
        assertThat(jdbc.queryForObject("SELECT amount FROM financial_movement WHERE id = ?",
                BigDecimal.class, original.getId())).isEqualByComparingTo("100");
        assertThatThrownBy(() -> operations.reversePayment(new ReversePaymentCommand(charge.getId(), original.getId(),
                "Otra reversa", key()), owner)).isInstanceOf(FinancialConflictException.class);
        assertThatThrownBy(() -> operations.reversePayment(new ReversePaymentCommand(charge.getId(), reversal.getId(),
                "Reversa de reversa", key()), owner)).isInstanceOf(FinancialConflictException.class);
        assertThat(count("financial_movement")).isEqualTo(2);
    }

    @Test
    void reversaExigeMotivoPagoExistenteYMismoCargoYPaciente() {
        Charge first = confirmed("100");
        Charge second = confirmed("100");
        FinancialMovement original = payment(first, "50", key());
        assertThatThrownBy(() -> operations.reversePayment(new ReversePaymentCommand(first.getId(), original.getId(),
                " ", key()), owner)).isInstanceOf(InvalidFinancialMovementException.class);
        assertThatThrownBy(() -> operations.reversePayment(new ReversePaymentCommand(first.getId(), Long.MAX_VALUE,
                "Error", key()), owner)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> operations.reversePayment(new ReversePaymentCommand(second.getId(), original.getId(),
                "Otro cargo", key()), owner)).isInstanceOf(FinancialConflictException.class);
        // Corrupcion sintetica admitida por la FK simple de V016: el servicio debe detectarla.
        jdbc.update("UPDATE financial_movement SET patient_id = ? WHERE id = ?", otherPatientId, original.getId());
        assertThatThrownBy(() -> operations.reversePayment(new ReversePaymentCommand(first.getId(), original.getId(),
                "Otro paciente", key()), owner)).isInstanceOf(FinancialConflictException.class);
        assertThat(count("financial_movement")).isEqualTo(1);
    }

    @Test
    void anulacionExigeMotivoYSinPagosValidosConservandoHistorial() {
        Charge charge = confirmed("100");
        FinancialMovement original = payment(charge, "40", key());
        assertThatThrownBy(() -> operations.cancelCharge(new CancelChargeCommand(charge.getId(), " ", key()), owner))
                .isInstanceOf(InvalidFinancialMovementException.class);
        assertThatThrownBy(() -> operations.cancelCharge(new CancelChargeCommand(charge.getId(), "Error", key()), owner))
                .isInstanceOf(FinancialConflictException.class);
        operations.reversePayment(new ReversePaymentCommand(charge.getId(), original.getId(), "Error", key()), owner);
        Charge cancelled = operations.cancelCharge(new CancelChargeCommand(charge.getId(), "Anulacion expresa", key()), owner);
        assertThat(cancelled.isCancelled()).isTrue();
        assertThat(cancelled.getCancelledByUserId()).isEqualTo(owner.userId());
        assertThat(cancelled.getCancellationReason()).isEqualTo("Anulacion expresa");
        assertThat(cancelled.getCancelledAt()).isEqualTo(cancelled.getUpdatedAt());
        assertThat(states.stateOf(charge.getId(), owner).status()).isEqualTo(ChargeStatus.ANULADO);
        assertThat(count("charge_item")).isEqualTo(1);
        assertThat(count("financial_movement")).isEqualTo(2);
        assertThatThrownBy(() -> operations.cancelCharge(new CancelChargeCommand(charge.getId(), "De nuevo", key()), owner))
                .isInstanceOf(FinancialConflictException.class);
    }

    @Test
    void reintentosDeLasCuatroOperacionesDevuelvenSnapshotOriginalSinDuplicados() {
        String key = "misma-clave-cuatro-operaciones";
        ConfirmChargeCommand confirm = command(null, null, "100.01", key);
        Charge charge = operations.confirmCharge(confirm, owner);
        RegisterPaymentCommand pay = new RegisterPaymentCommand(charge.getId(), PaymentMethod.CARD,
                new BigDecimal("100.01"), "Pago", key);
        FinancialMovement payment = operations.registerPayment(pay, owner);
        ReversePaymentCommand reverse = new ReversePaymentCommand(charge.getId(), payment.getId(), "Error", key);
        FinancialMovement reversal = operations.reversePayment(reverse, owner);
        CancelChargeCommand cancel = new CancelChargeCommand(charge.getId(), "Error", key);
        Charge cancelled = operations.cancelCharge(cancel, owner);

        assertThat(operations.confirmCharge(confirm, owner)).usingRecursiveComparison().isEqualTo(charge);
        assertThat(operations.registerPayment(pay, owner)).usingRecursiveComparison().isEqualTo(payment);
        assertThat(operations.reversePayment(reverse, owner)).usingRecursiveComparison().isEqualTo(reversal);
        assertThat(operations.cancelCharge(cancel, owner)).usingRecursiveComparison().isEqualTo(cancelled);
        assertThat(operations.confirmCharge(confirm, owner).isCancelled()).isFalse();
        assertThat(count("charge")).isEqualTo(1);
        assertThat(count("financial_movement")).isEqualTo(2);
        assertThat(count("http_idempotency")).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(response_body->'result'->'amount') "
                + "FROM http_idempotency WHERE operation = 'FIN_REGISTER_PAYMENT'", String.class)).isEqualTo("string");
    }

    @Test
    void claveEstaSeparadaPorActorYElAccesoSeRevalidaContraDatosPersistidos() {
        ConfirmChargeCommand command = command(ownAppointmentId, dentistId, "100", "shared");
        Charge first = operations.confirmCharge(command, owner);
        Charge second = operations.confirmCharge(command, reception);
        assertThat(second.getId()).isNotEqualTo(first.getId());
        RegisterPaymentCommand pay = new RegisterPaymentCommand(first.getId(), PaymentMethod.CASH,
                BigDecimal.ONE, null, "pay");
        operations.registerPayment(pay, owner);
        assertThat(states.stateOf(first.getId(), dentist).pendingBalance()).isEqualByComparingTo("99");
        // Cambio sintetico de asignacion: ni siquiera el acceso de lectura propio debe sobrevivir.
        jdbc.update("UPDATE appointment SET dentist_id = ? WHERE id = ?", otherDentistId, ownAppointmentId);
        assertThatThrownBy(() -> states.stateOf(first.getId(), dentist)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> operations.registerPayment(pay, dentist))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThat(count("financial_movement")).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Write.class)
    void mismaClaveConSolicitudDiferenteEsConflicto(Write write) {
        Charge charge = confirmed("100");
        FinancialMovement original = write == Write.REVERSE ? payment(charge, "10", key()) : null;
        switch (write) {
            case CONFIRM -> {
                operations.confirmCharge(command(null, null, "10", "same"), owner);
                assertThatThrownBy(() -> operations.confirmCharge(command(null, null, "11", "same"), owner))
                        .isInstanceOf(FinancialConflictException.class);
            }
            case PAYMENT -> {
                payment(charge, "10", "same");
                assertThatThrownBy(() -> payment(charge, "11", "same")).isInstanceOf(FinancialConflictException.class);
            }
            case REVERSE -> {
                operations.reversePayment(new ReversePaymentCommand(charge.getId(), original.getId(), "A", "same"), owner);
                assertThatThrownBy(() -> operations.reversePayment(new ReversePaymentCommand(charge.getId(), original.getId(),
                        "B", "same"), owner)).isInstanceOf(FinancialConflictException.class);
            }
            case CANCEL -> {
                operations.cancelCharge(new CancelChargeCommand(charge.getId(), "A", "same"), owner);
                assertThatThrownBy(() -> operations.cancelCharge(new CancelChargeCommand(charge.getId(), "B", "same"), owner))
                        .isInstanceOf(FinancialConflictException.class);
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM http_idempotency WHERE idempotency_key = 'same'",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void falloEnSegundoConceptoRevierteCargoYPrimerConceptoYLiberaClave() {
        String key = "retry-concept";
        ConfirmChargeCommand invalid = new ConfirmChargeCommand(patientId, null,
                List.of(item(null, "Valido", 1, "10"), item(null, "  ", 1, "10")), null, key);
        assertThatThrownBy(() -> operations.confirmCharge(invalid, owner)).isInstanceOf(InvalidChargeException.class);
        assertThat(count("charge")).isZero();
        assertThat(count("charge_item")).isZero();
        assertThat(count("http_idempotency")).isZero();
        assertThat(operations.confirmCharge(command(null, null, "20", key), owner).getId()).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(Write.class)
    void falloAlRegistrarIdempotenciaRevierteTodaLaOperacionYPermiteReintento(Write write) {
        Charge charge = write == Write.CONFIRM ? null : confirmed("100");
        FinancialMovement original = write == Write.REVERSE ? payment(charge, "10", key()) : null;
        long chargesBefore = count("charge");
        long itemsBefore = count("charge_item");
        long movementsBefore = count("financial_movement");
        long keysBefore = count("http_idempotency");
        String key = "fail-after-write";
        Runnable operation = switch (write) {
            case CONFIRM -> () -> operations.confirmCharge(command(null, null, "100", key), owner);
            case PAYMENT -> () -> payment(charge, "10", key);
            case REVERSE -> () -> operations.reversePayment(new ReversePaymentCommand(charge.getId(), original.getId(), "Error", key), owner);
            case CANCEL -> () -> operations.cancelCharge(new CancelChargeCommand(charge.getId(), "Error", key), owner);
        };
        // Fault injection real exclusivamente en la BD efimera: falla DESPUES del flush financiero.
        jdbc.execute("ALTER TABLE http_idempotency ADD CONSTRAINT financial_test_fault "
                + "CHECK (idempotency_key <> 'fail-after-write')");
        try {
            assertThatThrownBy(operation::run).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(count("charge")).isEqualTo(chargesBefore);
            assertThat(count("charge_item")).isEqualTo(itemsBefore);
            assertThat(count("financial_movement")).isEqualTo(movementsBefore);
            assertThat(count("http_idempotency")).isEqualTo(keysBefore);
            if (charge != null) {
                assertThat(jdbc.queryForObject("SELECT cancelled_at IS NULL FROM charge WHERE id = ?",
                        Boolean.class, charge.getId())).isTrue();
            }
        } finally {
            jdbc.execute("ALTER TABLE http_idempotency DROP CONSTRAINT financial_test_fault");
        }
        operation.run();
        assertThat(count("http_idempotency")).isEqualTo(keysBefore + 1);
    }
}

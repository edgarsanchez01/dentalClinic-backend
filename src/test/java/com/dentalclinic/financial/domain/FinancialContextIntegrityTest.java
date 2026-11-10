package com.dentalclinic.financial.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Invariantes del contexto financiero (UX-05B.1, Etapa 2).
 *
 * <p>Estas pruebas construyen movimientos con las factories reales del dominio. Cubren los casos
 * que la base no puede impedir con constraints: un movimiento de otro cargo, una reversa cruzada,
 * una reversa parcial, la doble reversa o conceptos que no suman el total.
 */
class FinancialContextIntegrityTest {

    private static final Long CHARGE = 100L;
    private static final Long OTHER_CHARGE = 200L;
    private static final Long PATIENT = 10L;
    private static final Long OTHER_PATIENT = 20L;
    private static final Long USER = 1L;
    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");

    private static FinancialMovement payment(Long chargeId, Long patientId, String amount) {
        return FinancialMovement.payment(chargeId, patientId, PaymentMethod.CASH,
                new BigDecimal(amount), null, USER, NOW);
    }

    private static FinancialMovement reversal(Long chargeId, Long patientId, Long targetId, String amount) {
        return FinancialMovement.reversal(chargeId, patientId, targetId, new BigDecimal(amount),
                "Correccion de cobro", USER, NOW);
    }

    private static FinancialMovement withId(FinancialMovement movement, Long id) {
        ReflectionTestUtils.setField(movement, "id", id);
        return movement;
    }

    @Test
    void unMovimientoDeOtroCargoNoSeAceptaEnElContexto() {
        FinancialMovement otherCharge = withId(payment(OTHER_CHARGE, PATIENT, "40"), 1L);
        assertThatThrownBy(() -> FinancialContextIntegrity.requireAppliedAmount(
                CHARGE, PATIENT, List.of(otherCharge)))
                .isInstanceOf(FinancialContextUnavailableException.class);
    }

    @Test
    void unMovimientoDeOtroPacienteNoSeAceptaEnElContexto() {
        FinancialMovement otherPatient = withId(payment(CHARGE, OTHER_PATIENT, "40"), 1L);
        assertThatThrownBy(() -> FinancialContextIntegrity.requireAppliedAmount(
                CHARGE, PATIENT, List.of(otherPatient)))
                .isInstanceOf(FinancialContextUnavailableException.class);
    }

    @Test
    void unaReversaDebeApuntarAUnPagoDelMismoCargo() {
        FinancialMovement payment = withId(payment(CHARGE, PATIENT, "40"), 1L);
        FinancialMovement foreignReversal = withId(reversal(OTHER_CHARGE, PATIENT, payment.getId(), "40"), 2L);
        assertThatThrownBy(() -> FinancialContextIntegrity.requireAppliedAmount(
                CHARGE, PATIENT, List.of(payment, foreignReversal)))
                .isInstanceOf(FinancialContextUnavailableException.class);
    }

    @Test
    void unaReversaConImporteDistintoAlPagoEsInconsistente() {
        FinancialMovement payment = withId(payment(CHARGE, PATIENT, "40"), 1L);
        FinancialMovement partialReversal = withId(reversal(CHARGE, PATIENT, payment.getId(), "30"), 2L);
        assertThatThrownBy(() -> FinancialContextIntegrity.requireAppliedAmount(
                CHARGE, PATIENT, List.of(payment, partialReversal)))
                .isInstanceOf(FinancialContextUnavailableException.class);
    }

    @Test
    void unaReversaCompletaAnulaElPagoEnElSaldoVerificado() {
        FinancialMovement payment = withId(payment(CHARGE, PATIENT, "40"), 1L);
        FinancialMovement reversal = withId(reversal(CHARGE, PATIENT, payment.getId(), "40"), 2L);
        assertThat(FinancialContextIntegrity.requireAppliedAmount(
                CHARGE, PATIENT, List.of(payment, reversal))).isEqualByComparingTo("0");
    }

    @Test
    void dosReversasDelMismoPagoSonInconsistentes() {
        FinancialMovement payment = withId(payment(CHARGE, PATIENT, "40"), 1L);
        FinancialMovement first = withId(reversal(CHARGE, PATIENT, payment.getId(), "40"), 2L);
        FinancialMovement second = withId(reversal(CHARGE, PATIENT, payment.getId(), "40"), 3L);
        assertThatThrownBy(() -> FinancialContextIntegrity.requireAppliedAmount(
                CHARGE, PATIENT, List.of(payment, first, second)))
                .isInstanceOf(FinancialContextUnavailableException.class);
    }

    @Test
    void unCargoSinConceptosNoEsUnContextoVerificable() {
        assertThatThrownBy(() -> FinancialContextIntegrity.requireChargeItems(
                CHARGE, new BigDecimal("100"), List.of()))
                .isInstanceOf(FinancialContextUnavailableException.class);
    }

    @Test
    void losConceptosDebenPertenecerAlCargoConsultado() {
        ChargeItem foreign = ChargeItem.create(OTHER_CHARGE, null, null, "Concepto", 1,
                new BigDecimal("100"), NOW);
        assertThatThrownBy(() -> FinancialContextIntegrity.requireChargeItems(
                CHARGE, new BigDecimal("100"), List.of(foreign)))
                .isInstanceOf(FinancialContextUnavailableException.class);
    }

    @Test
    void laSumaDeConceptosDebeCoincidirConElTotalConfirmado() {
        ChargeItem item = ChargeItem.create(CHARGE, null, null, "Concepto", 1,
                new BigDecimal("90"), NOW);
        assertThatThrownBy(() -> FinancialContextIntegrity.requireChargeItems(
                CHARGE, new BigDecimal("100"), List.of(item)))
                .isInstanceOf(FinancialContextUnavailableException.class);
    }
}

package com.dentalclinic.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.dentalclinic.financial.domain.ChargeFinancialState;
import com.dentalclinic.financial.domain.ChargeStatus;
import com.dentalclinic.financial.domain.ChargeStatusCalculator;
import com.dentalclinic.financial.domain.InvalidChargeException;

class ChargeStatusCalculatorTest {

    @Test
    void sin_abonos_es_pendiente_con_saldo_igual_al_total() {
        ChargeFinancialState state =
                ChargeStatusCalculator.evaluate(new BigDecimal("1000.00"), BigDecimal.ZERO, false);

        assertThat(state.status()).isEqualTo(ChargeStatus.PENDIENTE);
        assertThat(state.appliedAmount()).isEqualByComparingTo("0");
        assertThat(state.pendingBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    void abono_parcial_es_parcial_y_resta_al_saldo() {
        ChargeFinancialState state = ChargeStatusCalculator.evaluate(
                new BigDecimal("1000.00"), new BigDecimal("300.00"), false);

        assertThat(state.status()).isEqualTo(ChargeStatus.PARCIAL);
        assertThat(state.pendingBalance()).isEqualByComparingTo("700.00");
    }

    @Test
    void abonos_iguales_al_total_es_pagado_con_saldo_cero() {
        ChargeFinancialState state = ChargeStatusCalculator.evaluate(
                new BigDecimal("1000.00"), new BigDecimal("1000.00"), false);

        assertThat(state.status()).isEqualTo(ChargeStatus.PAGADO);
        assertThat(state.pendingBalance()).isEqualByComparingTo("0");
    }

    @Test
    void comparacion_ignora_escala_sin_redondear() {
        ChargeFinancialState state = ChargeStatusCalculator.evaluate(
                new BigDecimal("100.0000"), new BigDecimal("100"), false);

        assertThat(state.status()).isEqualTo(ChargeStatus.PAGADO);
        assertThat(state.pendingBalance()).isEqualByComparingTo("0");
    }

    @Test
    void cargo_anulado_es_anulado_y_sin_saldo_pendiente() {
        ChargeFinancialState state =
                ChargeStatusCalculator.evaluate(new BigDecimal("1000.00"), BigDecimal.ZERO, true);

        assertThat(state.status()).isEqualTo(ChargeStatus.ANULADO);
        assertThat(state.pendingBalance()).isEqualByComparingTo("0");
    }

    @Test
    void sobrepago_se_rechaza_sin_tolerancia() {
        assertThatThrownBy(() -> ChargeStatusCalculator.evaluate(
                new BigDecimal("1000.00"), new BigDecimal("1000.01"), false))
                .isInstanceOf(InvalidChargeException.class)
                .hasMessageContaining("sobrepago");
    }

    @Test
    void montos_nulos_o_negativos_se_rechazan() {
        assertThatThrownBy(() -> ChargeStatusCalculator.evaluate(null, BigDecimal.ZERO, false))
                .isInstanceOf(InvalidChargeException.class);
        assertThatThrownBy(() -> ChargeStatusCalculator.evaluate(BigDecimal.TEN, null, false))
                .isInstanceOf(InvalidChargeException.class);
        assertThatThrownBy(() -> ChargeStatusCalculator.evaluate(new BigDecimal("-1"),
                BigDecimal.ZERO, false))
                .isInstanceOf(InvalidChargeException.class);
        assertThatThrownBy(() -> ChargeStatusCalculator.evaluate(BigDecimal.TEN,
                new BigDecimal("-1"), false))
                .isInstanceOf(InvalidChargeException.class);
    }

    @Test
    void cargo_anulado_con_pagos_validos_no_oculta_inconsistencia() {
        assertThatThrownBy(() -> ChargeStatusCalculator.evaluate(
                new BigDecimal("1000.00"), new BigDecimal("300.00"), true))
                .isInstanceOf(InvalidChargeException.class)
                .hasMessageContaining("anulado");
    }

    @Test
    void cargo_cero_y_decimales_fuera_de_politica_se_rechazan() {
        assertThatThrownBy(() -> ChargeStatusCalculator.evaluate(BigDecimal.ZERO, BigDecimal.ZERO, false))
                .isInstanceOf(InvalidChargeException.class);
        assertThatThrownBy(() -> ChargeStatusCalculator.evaluate(BigDecimal.TEN,
                new BigDecimal("0.001"), false))
                .isInstanceOf(InvalidChargeException.class);
    }

    @Test
    void resta_decimal_es_exacta_y_no_usa_punto_flotante() {
        ChargeFinancialState state = ChargeStatusCalculator.evaluate(
                new BigDecimal("0.30"), new BigDecimal("0.10"), false);

        assertThat(state.status()).isEqualTo(ChargeStatus.PARCIAL);
        assertThat(state.pendingBalance()).isEqualByComparingTo("0.20");
    }
}

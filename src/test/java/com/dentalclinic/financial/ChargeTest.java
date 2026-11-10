package com.dentalclinic.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.InvalidChargeException;

class ChargeTest {

    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");

    @Test
    void create_confirmed_requires_positive_total() {
        assertThatThrownBy(() -> Charge.createConfirmed(1L, null, BigDecimal.ZERO, null, 9L, NOW))
                .isInstanceOf(InvalidChargeException.class);
        assertThatThrownBy(() -> Charge.createConfirmed(1L, null, new BigDecimal("-0.01"), null, 9L, NOW))
                .isInstanceOf(InvalidChargeException.class);
        assertThatThrownBy(() -> Charge.createConfirmed(1L, null, null, null, 9L, NOW))
                .isInstanceOf(InvalidChargeException.class);
    }

    @Test
    void create_confirmed_enforces_two_decimal_business_scale() {
        assertThatThrownBy(() ->
                Charge.createConfirmed(1L, null, new BigDecimal("100.001"), null, 9L, NOW))
                .isInstanceOf(InvalidChargeException.class)
                .hasMessageContaining("decimales");
    }

    @Test
    void create_confirmed_defaults_to_mxn_and_allows_optional_appointment() {
        Charge withAppointment = Charge.createConfirmed(1L, 55L, new BigDecimal("1500.00"), "nota", 9L, NOW);
        assertThat(withAppointment.getCurrency()).isEqualTo("MXN");
        assertThat(withAppointment.getAppointmentId()).isEqualTo(55L);
        assertThat(withAppointment.getTotalAmount()).isEqualByComparingTo("1500.00");
        assertThat(withAppointment.getConfirmedAt()).isEqualTo(NOW);
        assertThat(withAppointment.getConfirmedByUserId()).isEqualTo(9L);
        assertThat(withAppointment.isCancelled()).isFalse();
        assertThat(withAppointment.getVersion()).isEqualTo(1);

        Charge withoutAppointment = Charge.createConfirmed(1L, null, new BigDecimal("10"), null, 9L, NOW);
        assertThat(withoutAppointment.getAppointmentId()).isNull();
    }

    @Test
    void create_confirmed_requires_patient_and_confirmer() {
        assertThatThrownBy(() -> Charge.createConfirmed(null, null, new BigDecimal("10"), null, 9L, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Charge.createConfirmed(1L, null, new BigDecimal("10"), null, null, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void cancel_requires_reason_and_is_single_shot() {
        Charge charge = Charge.createConfirmed(1L, null, new BigDecimal("100"), null, 9L, NOW);

        assertThatThrownBy(() -> charge.cancel(9L, "  ", NOW))
                .isInstanceOf(InvalidChargeException.class);
        assertThat(charge.isCancelled()).isFalse();

        charge.cancel(9L, "Paciente cancela tratamiento", NOW);
        assertThat(charge.isCancelled()).isTrue();
        assertThat(charge.getCancelledByUserId()).isEqualTo(9L);
        assertThat(charge.getCancellationReason()).isEqualTo("Paciente cancela tratamiento");

        assertThatThrownBy(() -> charge.cancel(9L, "otra vez", NOW))
                .isInstanceOf(InvalidChargeException.class);
    }

    @Test
    void charge_does_not_persist_derived_status_or_balance() {
        assertThat(fieldNames(Charge.class))
                .doesNotContain("status", "balance", "saldo", "pendingAmount", "paidAmount");
    }

    private static java.util.List<String> fieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields()).map(Field::getName).toList();
    }
}

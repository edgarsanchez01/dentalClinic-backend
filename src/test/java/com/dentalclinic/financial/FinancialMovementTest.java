package com.dentalclinic.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.dentalclinic.financial.application.command.ReversePaymentCommand;
import com.dentalclinic.financial.domain.FinancialMovement;
import com.dentalclinic.financial.domain.FinancialMovementType;
import com.dentalclinic.financial.domain.InvalidFinancialMovementException;
import com.dentalclinic.financial.domain.MovementDirection;
import com.dentalclinic.financial.domain.PaymentMethod;

class FinancialMovementTest {

    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");

    @Test
    void payment_is_an_inflow_with_method_and_optional_reason() {
        FinancialMovement payment = FinancialMovement.payment(
                1L, 1L, PaymentMethod.CASH, new BigDecimal("500.00"), null, 9L, NOW);
        assertThat(payment.getType()).isEqualTo(FinancialMovementType.PAYMENT);
        assertThat(payment.getDirection()).isEqualTo(MovementDirection.IN);
        assertThat(payment.getPaymentMethod()).isEqualTo(PaymentMethod.CASH);
        assertThat(payment.getReversesMovementId()).isNull();
        assertThat(payment.getReason()).isNull();
        assertThat(payment.getAmount()).isEqualByComparingTo("500.00");
    }

    @Test
    void payment_requires_method_and_positive_amount() {
        assertThatThrownBy(() -> FinancialMovement.payment(
                1L, 1L, null, new BigDecimal("10"), null, 9L, NOW))
                .isInstanceOf(InvalidFinancialMovementException.class);
        assertThatThrownBy(() -> FinancialMovement.payment(
                1L, 1L, PaymentMethod.CARD, BigDecimal.ZERO, null, 9L, NOW))
                .isInstanceOf(InvalidFinancialMovementException.class);
        assertThatThrownBy(() -> FinancialMovement.payment(
                1L, 1L, PaymentMethod.TRANSFER, new BigDecimal("1.005"), null, 9L, NOW))
                .isInstanceOf(InvalidFinancialMovementException.class)
                .hasMessageContaining("decimales");
    }

    @Test
    void reversal_is_an_outflow_requiring_target_and_reason() {
        FinancialMovement reversal = FinancialMovement.reversal(
                1L, 1L, 50L, new BigDecimal("500.00"), "Pago duplicado", 9L, NOW);
        assertThat(reversal.getType()).isEqualTo(FinancialMovementType.REVERSAL);
        assertThat(reversal.getDirection()).isEqualTo(MovementDirection.OUT);
        assertThat(reversal.getPaymentMethod()).isNull();
        assertThat(reversal.getReversesMovementId()).isEqualTo(50L);
        assertThat(reversal.getReason()).isEqualTo("Pago duplicado");
    }

    @Test
    void reversal_requires_reason_and_target_movement() {
        assertThatThrownBy(() -> FinancialMovement.reversal(
                1L, 1L, 50L, new BigDecimal("10"), "  ", 9L, NOW))
                .isInstanceOf(InvalidFinancialMovementException.class);
        assertThatThrownBy(() -> FinancialMovement.reversal(
                1L, 1L, null, new BigDecimal("10"), "motivo", 9L, NOW))
                .isInstanceOf(InvalidFinancialMovementException.class);
    }

    @Test
    void movement_is_immutable_no_updatable_fields() {
        assertThat(fieldNames(FinancialMovement.class)).doesNotContain("updatedAt");
        PaymentMethod method = PaymentMethod.CASH;
        assertThat(method).isIn(PaymentMethod.CASH, PaymentMethod.CARD, PaymentMethod.TRANSFER, PaymentMethod.OTHER);
    }

    private static java.util.List<String> fieldNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields()).map(Field::getName).toList();
    }

    @Test
    void contrato_de_reversa_completa_no_acepta_importe_parcial() {
        ReversePaymentCommand command = new ReversePaymentCommand(1L, 50L, "Duplicado", "key");

        assertThat(command.movementId()).isEqualTo(50L);
        assertThat(Arrays.stream(ReversePaymentCommand.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
                .containsExactly("chargeId", "movementId", "reason", "idempotencyKey");
    }
}

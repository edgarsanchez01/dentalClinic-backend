package com.dentalclinic.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.dentalclinic.financial.domain.ChargeItem;
import com.dentalclinic.financial.domain.InvalidChargeException;

class ChargeItemTest {

    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");

    @Test
    void line_total_is_exact_product_of_quantity_and_unit_price() {
        ChargeItem item = ChargeItem.create(1L, null, null, "Limpieza", 3, new BigDecimal("250.50"), NOW);
        assertThat(item.getLineTotal()).isEqualByComparingTo("751.50");
        assertThat(item.getQuantity()).isEqualTo(3);
        assertThat(item.getUnitPrice()).isEqualByComparingTo("250.50");
    }

    @Test
    void quantity_must_be_positive() {
        assertThatThrownBy(() ->
                ChargeItem.create(1L, null, null, "Limpieza", 0, new BigDecimal("10"), NOW))
                .isInstanceOf(InvalidChargeException.class);
        assertThatThrownBy(() ->
                ChargeItem.create(1L, null, null, "Limpieza", -1, new BigDecimal("10"), NOW))
                .isInstanceOf(InvalidChargeException.class);
    }

    @Test
    void description_is_mandatory() {
        assertThatThrownBy(() ->
                ChargeItem.create(1L, null, null, "   ", 1, new BigDecimal("10"), NOW))
                .isInstanceOf(InvalidChargeException.class);
        assertThatThrownBy(() ->
                ChargeItem.create(1L, null, null, null, 1, new BigDecimal("10"), NOW))
                .isInstanceOf(InvalidChargeException.class);
    }

    @Test
    void unit_price_must_be_non_negative_and_two_decimals() {
        assertThatThrownBy(() ->
                ChargeItem.create(1L, null, null, "X", 1, new BigDecimal("-1"), NOW))
                .isInstanceOf(InvalidChargeException.class);
        assertThatThrownBy(() ->
                ChargeItem.create(1L, null, null, "X", 1, new BigDecimal("10.123"), NOW))
                .isInstanceOf(InvalidChargeException.class)
                .hasMessageContaining("decimales");
    }

    @Test
    void historical_snapshot_keeps_optional_catalog_and_dentist_references() {
        ChargeItem item = ChargeItem.create(1L, 77L, 42L, "Endodoncia", 1, new BigDecimal("3000"), NOW);
        assertThat(item.getServiceCatalogItemId()).isEqualTo(77L);
        assertThat(item.getTreatingDentistId()).isEqualTo(42L);

        ChargeItem plain = ChargeItem.create(1L, null, null, "Consulta", 1, new BigDecimal("500"), NOW);
        assertThat(plain.getServiceCatalogItemId()).isNull();
        assertThat(plain.getTreatingDentistId()).isNull();
    }
}

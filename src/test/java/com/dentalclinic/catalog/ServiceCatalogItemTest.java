package com.dentalclinic.catalog;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import com.dentalclinic.catalog.domain.InvalidCatalogItemException;
import com.dentalclinic.catalog.domain.ServiceCatalogItem;

class ServiceCatalogItemTest {
    @Test
    void domain_enforces_invariants_even_without_http_validation() {
        Instant now = Instant.parse("2026-10-10T00:00:00Z");
        assertThatThrownBy(() -> ServiceCatalogItem.create(" ", null, BigDecimal.ONE, 30, now))
                .isInstanceOf(InvalidCatalogItemException.class);
        assertThatThrownBy(() -> ServiceCatalogItem.create("X", null, new BigDecimal("-1"), 30, now))
                .isInstanceOf(InvalidCatalogItemException.class);
        assertThatThrownBy(() -> ServiceCatalogItem.create("X", null, BigDecimal.ONE, 0, now))
                .isInstanceOf(InvalidCatalogItemException.class);
        ServiceCatalogItem item = ServiceCatalogItem.create("  Consulta  ", " ", BigDecimal.ZERO, 20, now);
        assertThat(item.getName()).isEqualTo("Consulta");
        assertThat(item.getDescription()).isNull();
        item.setActive(false, now);
        item.update("Consulta", "Nueva descripción", new BigDecimal("12.3456"), 25, now);
        assertThat(item.isActive()).isFalse();
        assertThat(item.getCreatedAt()).isEqualTo(now);
    }
}

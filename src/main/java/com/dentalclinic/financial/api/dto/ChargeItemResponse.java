package com.dentalclinic.financial.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.dentalclinic.financial.domain.ChargeItem;

public record ChargeItemResponse(Long id, Long chargeId, Long serviceCatalogItemId, Long treatingDentistId,
        String description, int quantity, BigDecimal unitPrice, BigDecimal lineTotal, Instant createdAt) {
    public static ChargeItemResponse from(ChargeItem item) {
        return new ChargeItemResponse(item.getId(), item.getChargeId(), item.getServiceCatalogItemId(),
                item.getTreatingDentistId(), item.getDescription(), item.getQuantity(), item.getUnitPrice(),
                item.getLineTotal(), item.getCreatedAt());
    }
}

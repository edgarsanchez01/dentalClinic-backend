package com.dentalclinic.catalog.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import com.dentalclinic.catalog.domain.ServiceCatalogItem;

public record ServiceResponse(Long id, String name, String description, BigDecimal referencePrice,
                              int durationMinutes, boolean active, Instant createdAt, Instant updatedAt) {
    public static ServiceResponse from(ServiceCatalogItem item) {
        return new ServiceResponse(item.getId(), item.getName(), item.getDescription(), item.getReferencePrice(),
                item.getDurationMinutes(), item.isActive(), item.getCreatedAt(), item.getUpdatedAt());
    }
}

package com.dentalclinic.catalog.api.dto;

import java.math.BigDecimal;
import com.dentalclinic.catalog.domain.ServiceCatalogItem;

/** Solo datos publicados del servicio activo; no estado administrativo ni metadatos internos. */
public record PublicServiceResponse(Long id, String name, String description, BigDecimal referencePrice,
                                    int durationMinutes) {
    public static PublicServiceResponse from(ServiceCatalogItem item) {
        return new PublicServiceResponse(item.getId(), item.getName(), item.getDescription(),
                item.getReferencePrice(), item.getDurationMinutes());
    }
}

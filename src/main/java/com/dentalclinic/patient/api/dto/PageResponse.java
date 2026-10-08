package com.dentalclinic.patient.api.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * Pagina de resultados. Fase 4 es el primer modulo paginado; cuando otro modulo lo necesite se
 * promocionara a un tipo comun en lugar de duplicarlo.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
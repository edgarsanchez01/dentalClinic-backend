package com.dentalclinic.clinical.api.dto;

import java.time.Instant;

/** Nota clinica inmutable con su autor (RF-CX-04). */
public record ClinicalNoteResponse(
        Long id,
        String content,
        Long authorDentistId,
        String authorName,
        Instant createdAt) {
}
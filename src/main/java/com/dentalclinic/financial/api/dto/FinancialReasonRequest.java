package com.dentalclinic.financial.api.dto;

import jakarta.validation.constraints.NotBlank;

/** Motivo compartido por anular y reversar; no acepta importe para una reversa parcial. */
public record FinancialReasonRequest(@NotBlank String reason) implements StrictFinancialRequest {
}

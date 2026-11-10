package com.dentalclinic.financial.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import com.dentalclinic.financial.application.command.ConfirmChargeCommand;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

public record ConfirmChargeRequest(
        @NotNull @Positive @JsonDeserialize(using = FinancialIntegerDeserializer.class) Long patientId,
        @Positive @JsonDeserialize(using = FinancialIntegerDeserializer.class) Long appointmentId,
        @NotEmpty List<@NotNull @Valid ChargeItemRequest> items,
        String notes) implements StrictFinancialRequest {

    public ConfirmChargeCommand toCommand(String idempotencyKey) {
        // Sin trim, redondeo ni reordenado: conserva los valores que usa el hash de Fase 2B.
        return new ConfirmChargeCommand(patientId, appointmentId,
                items.stream().map(ChargeItemRequest::toData).toList(), notes, idempotencyKey);
    }
}

package com.dentalclinic.financial.application;

import java.time.Instant;
import java.util.List;

import com.dentalclinic.financial.domain.FinancialIntentOperation;
import com.dentalclinic.financial.domain.FinancialIntentState;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Proyeccion publica cerrada: nunca serializar FinancialIntent o HttpIdempotency. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FinancialIntentView(FinancialIntentOperation operation, String intentId, FinancialIntentState state,
        Instant registeredAt, Instant closedAt, Instant rejectedAttemptAt, Rejection rejection, Effect effect) {
    public record Rejection(String code, int httpStatus) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Effect(Long chargeId, Long movementId) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Page(List<FinancialIntentView> items, String nextCursor) { }
}

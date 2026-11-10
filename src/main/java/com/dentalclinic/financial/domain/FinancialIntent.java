package com.dentalclinic.financial.domain;

import java.time.Instant;

/**
 * Modelo interno inmutable de AD-34, no un DTO HTTP ni un recibo de ejecucion.
 * JSON como String preserva los numeros exactos de JSONB sin pasar por Map/Double.
 * B1 solo registra OPEN; no expone mutadores, cierres, borrado ni purga.
 */
public record FinancialIntent(Long actorUserId, FinancialIntentOperation operation,
        String idempotencyKey, int commandVersion, int hashVersion, String requestHash,
        String commandBody, FinancialIntentDisposition disposition, Instant registeredAt,
        Instant closedAt, Instant rejectedAttemptAt, String rejectionCode,
        Integer rejectionHttpStatus, Instant commandPurgedAt) {

    /** Evita filtrar el comando, notas, importes o hash al registrar accidentalmente el modelo. */
    @Override
    public String toString() {
        return "FinancialIntent[operation=" + operation + ", disposition=" + disposition + "]";
    }
}

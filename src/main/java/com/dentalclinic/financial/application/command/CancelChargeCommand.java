package com.dentalclinic.financial.application.command;

/**
 * Anulacion explicita de un cargo (contrato de Fase 2B; no ejecuta nada aqui).
 *
 * <p>Solo OWNER. No se permite anular un cargo que conserva pagos validos. El motivo es
 * obligatorio.
 *
 * @param chargeId       cargo a anular
 * @param reason         motivo; obligatorio
 * @param idempotencyKey clave del cliente; obligatoria y unica por actor+operacion
 */
public record CancelChargeCommand(Long chargeId, String reason, String idempotencyKey) {
}

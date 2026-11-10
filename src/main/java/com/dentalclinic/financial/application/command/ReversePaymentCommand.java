package com.dentalclinic.financial.application.command;

/**
 * Reversa de un pago previo (contrato de Fase 2B; no ejecuta nada aqui).
 *
 * <p>Un movimiento admite una sola reversa ({@code UNIQUE(reverses_movement_id)}, V016). La
 * reversa es COMPLETA, no es un nuevo ingreso y el pago original permanece intacto.
 * El contrato no acepta importe: 2B debe cargar el PAYMENT persistido y compensar exactamente
 * su importe, cargo y paciente, bajo el bloqueo del cargo. No admite reversas de reversas.
 *
 * @param chargeId       cargo del pago a reversar
 * @param movementId     {@code PAYMENT} original que se compensa
 * @param reason         motivo; obligatorio
 * @param idempotencyKey clave del cliente; obligatoria y unica por actor+operacion
 */
public record ReversePaymentCommand(Long chargeId, Long movementId, String reason,
                                    String idempotencyKey) {
}

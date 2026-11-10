package com.dentalclinic.financial.application.command;

import java.math.BigDecimal;

import com.dentalclinic.financial.domain.PaymentMethod;

/**
 * Registro manual de un abono/pago parcial (contrato de Fase 2B; no ejecuta nada aqui).
 *
 * <p>El importe no puede superar el saldo pendiente del cargo (RF-PY-07). El metodo es
 * obligatorio y NO determina la liquidacion del cargo.
 *
 * @param chargeId       cargo confirmado destino
 * @param method         metodo manual del cobro recibido
 * @param amount         importe positivo
 * @param reason         motivo/nota opcional
 * @param idempotencyKey clave del cliente; obligatoria y unica por actor+operacion (reusa
 *                       {@code http_idempotency}, V006)
 */
public record RegisterPaymentCommand(Long chargeId, PaymentMethod method, BigDecimal amount,
                                     String reason, String idempotencyKey) {
}

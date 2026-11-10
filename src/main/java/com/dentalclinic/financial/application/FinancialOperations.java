package com.dentalclinic.financial.application;

import com.dentalclinic.financial.application.command.CancelChargeCommand;
import com.dentalclinic.financial.application.command.ConfirmChargeCommand;
import com.dentalclinic.financial.application.command.RegisterPaymentCommand;
import com.dentalclinic.financial.application.command.ReversePaymentCommand;
import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.FinancialMovement;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/**
 * Contrato interno de las operaciones de ESCRITURA financiera (Fase 2B).
 *
 * <p>Implementado por {@link FinancialOperationsService}. Cada llamada es una transaccion propia
 * READ_COMMITTED: datos financieros y respuesta idempotente se confirman o revierten juntos.
 * Las respuestas son internas; la integracion HTTP/DTO corresponde a Fase 3.
 *
 * <p>Requisitos obligatorios de cada implementacion (Fase 2B):
 *
 * <ul>
 *   <li><strong>Autorizacion:</strong> {@link FinancialAuthorizationService} — consulta, creacion
 *       y pagos con gates separados; OWNER/RECEPTIONIST globales, DENTIST solo atenciones propias
 *       verificadas sin cargos mixtos. Anulacion y reversa solo OWNER.</li>
 *   <li><strong>Idempotencia:</strong> reutilizar {@code http_idempotency} (V006) con
 *       {@code UNIQUE(actor_user_id, operation, idempotency_key)}; consumir la clave de forma
 *       atomica dentro de la MISMA transaccion de negocio, sin crear una tabla nueva.</li>
 *   <li><strong>Concurrencia:</strong> tomar el bloqueo pesimista del cargo
 *       ({@code ChargeRepository#findByIdForUpdate}) antes de registrar un pago, reversar o anular, para
 *       serializar operaciones sobre el mismo cargo.</li>
 *   <li><strong>Sobrepago:</strong> rechazar un pago cuyo importe supere el saldo pendiente
 *       calculado con {@link com.dentalclinic.financial.domain.ChargeStatusCalculator} (sin
 *       carrera entre la lectura del saldo y la escritura).</li>
 *   <li><strong>Referencias:</strong> validar paciente/cita con {@link ChargeReferenceValidator};
 *       total del cargo = suma de conceptos.</li>
 *   <li><strong>Inmutabilidad:</strong> los movimientos son append-only; no se actualiza ni borra
 *       un movimiento; una sola reversa COMPLETA por pago. Su importe, cargo y paciente se
 *       obtienen del PAYMENT persistido, nunca del cliente; no se reversa una REVERSAL.</li>
 * </ul>
 */
public interface FinancialOperations {

    /** Alta manual de un cargo confirmado con sus conceptos. */
    Charge confirmCharge(ConfirmChargeCommand command, AuthenticatedUser actor);

    /** Registro manual de un abono/pago parcial sobre un cargo confirmado. */
    FinancialMovement registerPayment(RegisterPaymentCommand command, AuthenticatedUser actor);

    /** Compensacion COMPLETA de un pago previo; no es un nuevo ingreso. */
    FinancialMovement reversePayment(ReversePaymentCommand command, AuthenticatedUser actor);

    /** Anulacion explicita de un cargo sin pagos validos. */
    Charge cancelCharge(CancelChargeCommand command, AuthenticatedUser actor);
}

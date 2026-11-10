package com.dentalclinic.financial.application.command;

import java.util.List;

/**
 * Alta manual de un cargo YA confirmado (contrato de Fase 2B; no ejecuta nada aqui).
 *
 * <p>El cargo nunca se genera automaticamente al reservar ni al completar una cita. El total se
 * deriva de la suma de los conceptos y debe coincidir con {@code items}.
 *
 * @param patientId     paciente real; obligatorio
 * @param appointmentId cita OPCIONAL del mismo paciente; puede ser {@code null}
 * @param items         conceptos; al menos uno
 * @param notes         nota administrativa opcional
 * @param idempotencyKey clave obligatoria por actor y operacion
 */
public record ConfirmChargeCommand(Long patientId, Long appointmentId, List<ChargeItemData> items,
                                   String notes, String idempotencyKey) {
    public ConfirmChargeCommand {
        // Los conceptos autorizados no pueden cambiar antes de persistirlos en 2B.
        if (items != null) {
            items = List.copyOf(items);
        }
    }
}

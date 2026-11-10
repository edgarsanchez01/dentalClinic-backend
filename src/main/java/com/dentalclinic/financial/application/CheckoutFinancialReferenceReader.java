package com.dentalclinic.financial.application;

import java.util.Optional;

import com.dentalclinic.financial.domain.FinancialMovementType;

/**
 * Puerto interno de lectura financiera para el registro durable del cierre (UX-05B.1, Etapa 3.2).
 *
 * <p>Mantiene el acceso a cargos y movimientos dentro del modulo {@code financial}: el modulo
 * {@code checkout} no importa repositorios ajenos. No autoriza, no abre transaccion, no calcula
 * saldos y no crea cargos ni pagos. El futuro coordinador conserva el gate y los locks.
 */
public interface CheckoutFinancialReferenceReader {

    Optional<ChargeReference> findCharge(Long chargeId);

    Optional<MovementReference> findMovement(Long movementId);

    /** Referencia minima del cargo para validar cita y paciente. */
    record ChargeReference(Long id, Long patientId, Long appointmentId) {
    }

    /** Referencia minima del movimiento; el cierre solo admite {@code PAYMENT}. */
    record MovementReference(Long id, Long chargeId, Long patientId, FinancialMovementType type) {
    }
}

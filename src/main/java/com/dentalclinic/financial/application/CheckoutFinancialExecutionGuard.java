package com.dentalclinic.financial.application;

/**
 * Contexto transaccional interno que autoriza las escrituras del cierre integrado.
 *
 * <p>El coordinador lo abre <strong>solo</strong> despues de resolver un replay y validar las
 * precondiciones de una ejecucion nueva. El participante financiero y el cierre durable lo
 * consultan antes de escribir, de modo que una transaccion {@code MANDATORY} por si sola no basta:
 * sin este contexto, una llamada directa al participante falla y revierte.
 *
 * <p>La implementacion vive en el modulo {@code checkout} y liga el contexto a la transaccion
 * fisica (no a un {@code ThreadLocal} que pueda sobrevivir al commit). La interfaz no expone
 * detalles de almacenamiento ni permite fabricar una autorizacion desde HTTP.
 */
public interface CheckoutFinancialExecutionGuard {

    /** Abre el contexto de una ejecucion nueva; falla si ya existe uno en la transaccion. */
    void open(Long appointmentId, Long actorUserId, int occurrenceVersion);

    /** Exige un contexto abierto que coincida con la cita, el actor y la ocurrencia. */
    void requireFinancialWrite(Long appointmentId, Long actorUserId, int occurrenceVersion);

    void expectFinancialResult(Long chargeId, Long paymentMovementId);

    /** Marca el cierre durable como registrado dentro de la misma ejecucion. */
    void markBusinessCompleted(Long appointmentId, Long actorUserId, int occurrenceVersion);

    /** Exige que el cierre durable se haya registrado antes de consumir la idempotencia. */
    void requireBusinessCompleted(Long appointmentId, Long actorUserId, int occurrenceVersion);
}

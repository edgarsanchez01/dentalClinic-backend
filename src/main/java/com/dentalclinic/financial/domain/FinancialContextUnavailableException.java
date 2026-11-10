package com.dentalclinic.financial.domain;

/**
 * El conjunto de cargos asociados a una atencion <strong>no puede verificarse integramente</strong>.
 *
 * <p>Se lanza cuando el contexto financiero de una cita no se puede devolver completo y coherente
 * para el actor (fail-closed). Casos tipicos:
 *
 * <ul>
 *   <li>un cargo asociado cuyo paciente no es el de la cita (referencia invalida que el esquema
 *       no puede expresar con FK simple);</li>
 *   <li>un cargo asociado que el actor no puede verificar por completo (por ejemplo, un cargo
 *       mixto para el {@code DENTIST}: el conjunto completo no es visible para el);</li>
 *   <li>un cargo con estado financiero inconsistente (sobrepago o cargo anulado con pagos
 *       validos).</li>
 * </ul>
 *
 * <p>El mensaje es <strong>generico</strong> a proposito: no revela que cargo concreto falla, ni
 * su importe, ni su estado, ni la razon de la inconsistencia. El cliente recibe un
 * {@code 409 FINANCIAL_CONTEXT_UNAVAILABLE} y ningun dato financiero parcial.
 *
 * <p>No es un conflicto de escritura: no se puede "reintentar con otra clave" para resolverlo. Es
 * la respuesta correcta a un contexto que no puede mostrarse sin mentir.
 */
public class FinancialContextUnavailableException extends RuntimeException {

    public FinancialContextUnavailableException() {
        super("No se pudo verificar por completo el contexto financiero de la atencion.");
    }
}

package com.dentalclinic.financial.domain;

/**
 * Clasificacion de cardinalidad de los cargos asociados a una atencion (UX-05B.1, Etapa 2).
 *
 * <p>Describe <strong>cuantos</strong> cargos recaen sobre la cita y si alguno sigue vigente. Es
 * una clasificacion <strong>estructural</strong>: no elige cargo, no ordena un cobro y no implica
 * ninguna regla de imputacion.
 *
 * <ul>
 *   <li>{@link #NO_ASSOCIATED_CHARGE}: la cita no tiene ningun cargo asociado.</li>
 *   <li>{@link #ONLY_CANCELLED}: tiene cargos, pero todos estan anulados.</li>
 *   <li>{@link #SINGLE_ACTIVE}: tiene exactamente un cargo y no esta anulado.</li>
 *   <li>{@link #MULTIPLE_ACTIVE}: tiene mas de un cargo y al menos uno no esta anulado.</li>
 * </ul>
 *
 * <p><strong>Ningun estado selecciona automaticamente un cargo para el cierre</strong> (D-01 sigue
 * abierta): la regla de que cargo es «el» cargo de una atencion es una decision de producto que
 * esta etapa no toma. El unico proposito de esta enumeracion es que la vista distinga el caso
 * trivial del caso que exige una decision humana.
 *
 * <p><strong>No se agrega saldo entre cargos.</strong> Cada cargo expone su propio total, monto
 * aplicado y saldo pendiente; ningun estado suma importes de cargos distintos (C-04: vacio no
 * significa saldo cero, y un saldo agregado seria una cifra inventada).
 */
public enum ChargeAssociationState {

    /** La atencion no tiene ningun cargo asociado: la vista esta vacia, no en cero. */
    NO_ASSOCIATED_CHARGE,

    /** Hay cargos asociados, pero todos estan anulados. */
    ONLY_CANCELLED,

    /** Hay exactamente un cargo asociado y no esta anulado. */
    SINGLE_ACTIVE,

    /** Hay mas de un cargo asociado y al menos uno no esta anulado. */
    MULTIPLE_ACTIVE
}

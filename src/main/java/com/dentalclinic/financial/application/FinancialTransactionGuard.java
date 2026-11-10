package com.dentalclinic.financial.application;

import java.sql.Connection;

import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Guardas para los participantes internos de escritura.
 *
 * <p>La propagacion {@code MANDATORY} la aplica Spring por proxy antes de entrar al metodo. Esta
 * guarda cubre los dos casos que la anotacion por si sola no distingue: una transaccion ambiente
 * de solo lectura y un aislamiento distinto de {@code READ_COMMITTED}. El aislamiento
 * {@code null} corresponde a una transaccion exterior sin nivel explicito y se acepta para no
 * imponer una politica distinta a quien ya delimitó la transaccion.
 */
final class FinancialTransactionGuard {

    private FinancialTransactionGuard() {
    }

    static void requireWritable() {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalTransactionStateException(
                    "La operacion financiera participante no admite una transaccion de solo lectura.");
        }
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (isolation != null && isolation != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalTransactionStateException(
                    "La operacion financiera participante exige aislamiento READ_COMMITTED.");
        }
    }
}

package com.dentalclinic.checkout.application;

import java.sql.Connection;

import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Guardas del registro durable del cierre.
 *
 * <p>La propagacion {@code MANDATORY} la aplica Spring por proxy. Esta guarda rechaza ademas una
 * transaccion ambiente de solo lectura o un aislamiento explicito distinto de
 * {@code READ_COMMITTED}. El aislamiento {@code null} corresponde a una transaccion exterior sin
 * nivel explicito y se acepta; el futuro coordinador debera declarar {@code READ_COMMITTED} de
 * forma explicita. Esta clase no comprueba el aislamiento fisico de la conexion.
 */
final class CheckoutTransactionGuard {

    private CheckoutTransactionGuard() {
    }

    static void requireWritable() {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalTransactionStateException(
                    "El cierre integrado requiere una transaccion de escritura.");
        }
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (isolation != null && isolation != Connection.TRANSACTION_READ_COMMITTED) {
            throw new IllegalTransactionStateException(
                    "El cierre integrado requiere aislamiento READ_COMMITTED.");
        }
    }
}

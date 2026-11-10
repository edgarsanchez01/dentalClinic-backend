package com.dentalclinic.financial.application;

import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.FinancialIntentClosedException;
import com.dentalclinic.financial.domain.FinancialIntentClosureBlockedException;
import com.dentalclinic.financial.domain.FinancialIntentClosureUncertainException;
import com.dentalclinic.financial.domain.FinancialIntentOperation;
import com.dentalclinic.financial.domain.FinancialMovement;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/**
 * Coordinacion B3 SIN transaccion ambiente. El proxy del ejecutor completa rollback
 * antes de que este coordinador intente un cierre en otra REQUIRES_NEW.
 * No reintenta ejecucion, no deduce rollback de una excepcion y no cierra por HTTP generico.
 */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class FinancialIntentExecutionService {
    private final FinancialIntentExecutionTransaction execution;
    private final FinancialIntentClosureService closures;

    FinancialIntentExecutionService(FinancialIntentExecutionTransaction execution, FinancialIntentClosureService closures) {
        this.execution = execution;
        this.closures = closures;
    }

    public Charge confirmCharge(String intentId, AuthenticatedUser actor) {
        return coordinate(actor, FinancialIntentOperation.FIN_CONFIRM_CHARGE, intentId,
                attempt -> execution.confirmCharge(intentId, actor, attempt));
    }

    public FinancialMovement registerPayment(String intentId, AuthenticatedUser actor) {
        return coordinate(actor, FinancialIntentOperation.FIN_REGISTER_PAYMENT, intentId,
                attempt -> execution.registerPayment(intentId, actor, attempt));
    }

    public FinancialMovement reversePayment(String intentId, AuthenticatedUser actor) {
        return coordinate(actor, FinancialIntentOperation.FIN_REVERSE_PAYMENT, intentId,
                attempt -> execution.reversePayment(intentId, actor, attempt));
    }

    public Charge cancelCharge(String intentId, AuthenticatedUser actor) {
        return coordinate(actor, FinancialIntentOperation.FIN_CANCEL_CHARGE, intentId,
                attempt -> execution.cancelCharge(intentId, actor, attempt));
    }

    private <T> T coordinate(AuthenticatedUser actor, FinancialIntentOperation operation, String key,
            Function<FinancialIntentExecutionAttempt, T> execute) {
        var attempt = new FinancialIntentExecutionAttempt(actor, operation, key);
        try {
            return execute.apply(attempt);
        } catch (RuntimeException failure) {
            if (!attempt.provesRollbackFor(failure)) {
                if (attempt.capturedBusinessFailure(failure)) throw new FinancialIntentClosureUncertainException();
                throw failure;
            }
            try {
                closures.rejectAfterRollback(attempt, failure);
            } catch (FinancialIntentClosedException | FinancialIntentClosureBlockedException knownState) {
                throw knownState;
            } catch (RuntimeException uncertainClosure) {
                // El fallo de finalizacion no se publica como REJECTED; no asumir el commit de cierre.
                throw new FinancialIntentClosureUncertainException();
            }
            throw failure; // Solo tras commit acreditado del cierre REJECTED.
        }
    }
}

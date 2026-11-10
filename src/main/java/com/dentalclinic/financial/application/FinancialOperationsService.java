package com.dentalclinic.financial.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.financial.application.command.CancelChargeCommand;
import com.dentalclinic.financial.application.command.ConfirmChargeCommand;
import com.dentalclinic.financial.application.command.RegisterPaymentCommand;
import com.dentalclinic.financial.application.command.ReversePaymentCommand;
import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.FinancialMovement;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/**
 * Fachada manual existente: mismas firmas, gates, snapshots y transaccion independiente.
 * B2 comparte ejecucion MANDATORY con intenciones sin llamar otra fachada REQUIRES_NEW.
 * Sin intencion registrada mantiene el contrato legacy; si existe, respeta su comando y cierre.
 */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
public class FinancialOperationsService implements FinancialOperations {
    private final ManualFinancialExecutionParticipant execution;

    FinancialOperationsService(ManualFinancialExecutionParticipant execution) {
        this.execution = execution;
    }

    @Override
    public Charge confirmCharge(ConfirmChargeCommand command, AuthenticatedUser actor) {
        return execution.confirmCharge(command, actor);
    }

    @Override
    public FinancialMovement registerPayment(RegisterPaymentCommand command, AuthenticatedUser actor) {
        return execution.registerPayment(command, actor);
    }

    @Override
    public FinancialMovement reversePayment(ReversePaymentCommand command, AuthenticatedUser actor) {
        return execution.reversePayment(command, actor);
    }

    @Override
    public Charge cancelCharge(CancelChargeCommand command, AuthenticatedUser actor) {
        return execution.cancelCharge(command, actor);
    }
}

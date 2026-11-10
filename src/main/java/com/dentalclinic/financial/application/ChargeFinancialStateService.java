package com.dentalclinic.financial.application;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.ChargeFinancialState;
import com.dentalclinic.financial.domain.ChargeStatusCalculator;
import com.dentalclinic.financial.infrastructure.repository.ChargeRepository;
import com.dentalclinic.financial.infrastructure.repository.FinancialMovementRepository;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/**
 * Servicio de consulta financiera: deriva total, monto aplicado, saldo pendiente y estado de un
 * cargo. Es <strong>solo lectura</strong>: no persiste estado ni saldo y no crea movimientos
 * (RF-PY-05/06).
 *
 * <p>El monto aplicado procede de
 * {@code FinancialMovementRepository#sumValidAppliedAmount(chargeId)}, cuya semantica
 * excluye pagos revertidos y no cuenta la reversa como ingreso (ver
 * {@link com.dentalclinic.financial.domain.ChargeStatusCalculator}).
 */
@Service
public class ChargeFinancialStateService {

    private final ChargeRepository chargeRepository;
    private final FinancialMovementRepository movementRepository;
    private final FinancialAuthorizationService authorization;

    public ChargeFinancialStateService(ChargeRepository chargeRepository,
                                       FinancialMovementRepository movementRepository,
                                       FinancialAuthorizationService authorization) {
        this.chargeRepository = chargeRepository;
        this.movementRepository = movementRepository;
        this.authorization = authorization;
    }

    /**
     * Estado del cargo aplicando autorizacion de visibilidad.
     *
     * @throws NotFoundException si el cargo no existe o no es visible para el actor
     */
    @Transactional(readOnly = true)
    public ChargeFinancialState stateOf(Long chargeId, AuthenticatedUser actor) {
        Charge charge = chargeRepository.findById(chargeId).orElseThrow(NotFoundException::new);
        authorization.requireView(actor, charge);
        return calculateState(charge);
    }

    /** Solo se invoca despues de cargar y autorizar el cargo; no es una entrada publica. */
    private ChargeFinancialState calculateState(Charge charge) {
        BigDecimal applied = movementRepository
                .sumValidAppliedAmount(charge.getId())
                .orElse(BigDecimal.ZERO);
        return ChargeStatusCalculator.evaluate(charge.getTotalAmount(), applied, charge.isCancelled());
    }
}

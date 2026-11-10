package com.dentalclinic.financial.application;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.financial.infrastructure.repository.ChargeRepository;
import com.dentalclinic.financial.infrastructure.repository.FinancialMovementRepository;

/** Implementacion package-private del puerto de lectura financiera para checkout. */
@Service
@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
class CheckoutFinancialReferenceReaderImpl implements CheckoutFinancialReferenceReader {

    private final ChargeRepository charges;
    private final FinancialMovementRepository movements;

    CheckoutFinancialReferenceReaderImpl(ChargeRepository charges,
            FinancialMovementRepository movements) {
        this.charges = charges;
        this.movements = movements;
    }

    @Override
    public Optional<ChargeReference> findCharge(Long chargeId) {
        return charges.findById(chargeId)
                .map(c -> new ChargeReference(c.getId(), c.getPatientId(), c.getAppointmentId()));
    }

    @Override
    public Optional<MovementReference> findMovement(Long movementId) {
        return movements.findById(movementId)
                .map(m -> new MovementReference(m.getId(), m.getChargeId(), m.getPatientId(),
                        m.getType()));
    }
}

package com.dentalclinic.checkout.application;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.dentalclinic.checkout.domain.AppointmentCheckout;
import com.dentalclinic.checkout.domain.ChargeDisposition;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;

/** Retired writer. Historical rows are only read; no new historical identities may be forged. */
@Service
class AppointmentCheckoutRegistrar {
    @Transactional(propagation = Propagation.MANDATORY)
    public AppointmentCheckout register(CheckoutDraft draft) {
        throw new ForbiddenOperationException("No se pueden registrar nuevos checkouts historicos.");
    }

    record CheckoutDraft(Long appointmentId, int occurrenceVersion, Long chargeId,
            Long paymentMovementId, ChargeDisposition chargeDisposition, Long actorUserId, Instant completedAt) { }
}

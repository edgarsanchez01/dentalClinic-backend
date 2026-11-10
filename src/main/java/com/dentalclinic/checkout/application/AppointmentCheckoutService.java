package com.dentalclinic.checkout.application;

import org.springframework.stereotype.Service;
import com.dentalclinic.checkout.api.dto.AppointmentCheckoutResponse;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/** Retired application entry point. Kept to fail closed for existing in-process callers. */
@Service
class AppointmentCheckoutService {
    AppointmentCheckoutResponse checkout(CheckoutCommand command, AuthenticatedUser actor) {
        throw new ForbiddenOperationException("El checkout integrado esta retirado. Usa /complete y /collection.");
    }
}

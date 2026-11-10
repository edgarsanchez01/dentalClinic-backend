package com.dentalclinic.checkout.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.checkout.api.dto.AppointmentCheckoutResponse;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/**
 * Coordinador del cierre integrado.
 *
 * <p>No abre ni hereda transaccion: exige {@code NEVER} y delega en
 * {@link AppointmentCheckoutService}, que abre la unica transaccion {@code READ_COMMITTED}. Asi una
 * transaccion ambiente del controlador o de un test no puede ampliar la frontera del cierre ni
 * provocar commits parciales. No captura errores para reintentar ni compensar: la excepcion sale
 * despues de que el interceptor haya hecho rollback.
 */
@Service
public class CheckoutCoordinator {

    private final AppointmentCheckoutService service;

    CheckoutCoordinator(AppointmentCheckoutService service) {
        this.service = service;
    }

    @Transactional(propagation = Propagation.NEVER)
    public AppointmentCheckoutResponse checkout(CheckoutCommand command, AuthenticatedUser actor) {
        throw new com.dentalclinic.identity.domain.exception.ForbiddenOperationException(
                "El checkout integrado esta retirado. Usa /complete y /collection.");
    }
}

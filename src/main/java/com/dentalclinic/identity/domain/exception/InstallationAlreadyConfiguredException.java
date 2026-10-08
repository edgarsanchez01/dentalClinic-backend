package com.dentalclinic.identity.domain.exception;

/**
 * La instalación ya está configurada ({@code installation_state.status = COMPLETED}).
 *
 * <p>Se traduce a <strong>403</strong>: el onboarding es una acción que solo puede ocurrir una vez,
 * y volver a intentarlo no es un conflicto de datos que el cliente pueda resolver, sino una acción
 * prohibida sobre el estado de la instalación. Es distinto del 409, reservado a perder una carrera
 * concurrente (ver {@link InstallationConcurrencyConflictException}).
 */
public class InstallationAlreadyConfiguredException extends RuntimeException {

    public InstallationAlreadyConfiguredException() {
        super("La instalación ya está configurada. El onboarding solo puede realizarse una vez.");
    }
}
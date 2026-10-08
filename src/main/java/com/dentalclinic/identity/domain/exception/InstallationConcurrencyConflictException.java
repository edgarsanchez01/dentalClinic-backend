package com.dentalclinic.identity.domain.exception;

/**
 * Otra petición completó el onboarding entre la comprobación previa y la toma del lock.
 *
 * <p>Se traduce a <strong>409</strong>: es una carrera concurrente, no una acción prohibida. El
 * cliente puede asumir que la instalación acaba de ser configurada por la otra petición.
 */
public class InstallationConcurrencyConflictException extends RuntimeException {

    public InstallationConcurrencyConflictException() {
        super("Otra petición completó la configuración al mismo tiempo. Vuelve a cargar el estado.");
    }
}
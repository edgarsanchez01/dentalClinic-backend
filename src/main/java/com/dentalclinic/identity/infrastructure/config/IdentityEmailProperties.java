package com.dentalclinic.identity.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración del envío de enlaces de identidad (F8B.2).
 *
 * <p>Ningún valor es una decisión de producto cerrada: el máximo de intentos y el backoff son
 * "configurables" (PRD §14.5/§34.12) y aquí se materializan como valores por defecto de
 * implementación sobrescribibles por entorno. Las credenciales SMTP <strong>no</strong> viven aquí:
 * reutilizan el adaptador {@code EmailSender} ({@code SPRING_MAIL_*}), y el canal solo existe si el
 * operador lo habilita.
 *
 * <p>Vive en el prefijo {@code dentalclinic.identity.email}, subárbol de
 * {@code dentalclinic.identity} que consume {@link IdentityLinkProperties}; ambos enlazan
 * subconjuntos disjuntos de propiedades.
 *
 * @param dispatchEnabled   si el job programado está activo (por defecto, no)
 * @param batchSize         filas reclamadas por tick
 * @param maxAttempts       intentos máximos antes de pasar a FAILED
 * @param initialBackoff    espera del primer reintento
 * @param backoffMultiplier factor de crecimiento exponencial
 * @param maxBackoff        tope del backoff
 * @param lease             vigencia del claim mientras se envía por SMTP
 * @param interval          periodo entre ticks del job
 * @param initialDelay      retardo del primer tick
 */
@ConfigurationProperties(prefix = "dentalclinic.identity.email")
public record IdentityEmailProperties(boolean dispatchEnabled, int batchSize, int maxAttempts,
        Duration initialBackoff, double backoffMultiplier, Duration maxBackoff, Duration lease,
        Duration interval, Duration initialDelay) {

    public IdentityEmailProperties {
        batchSize = batchSize <= 0 ? 20 : batchSize;
        maxAttempts = maxAttempts <= 0 ? 4 : maxAttempts;
        initialBackoff = initialBackoff == null ? Duration.ofMinutes(1) : initialBackoff;
        backoffMultiplier = backoffMultiplier <= 0 ? 5.0 : backoffMultiplier;
        maxBackoff = maxBackoff == null ? Duration.ofHours(1) : maxBackoff;
        lease = lease == null ? Duration.ofMinutes(5) : lease;
        interval = interval == null ? Duration.ofSeconds(60) : interval;
        initialDelay = initialDelay == null ? Duration.ofSeconds(30) : initialDelay;
        if (initialBackoff.toMillis() <= 0 || maxBackoff.toMillis() <= 0
                || lease.toMillis() <= 0 || interval.toMillis() <= 0 || initialDelay.isNegative()
                || !Double.isFinite(backoffMultiplier) || backoffMultiplier < 1) {
            throw new IllegalArgumentException(
                    "Configuración de identidad email inválida: duraciones positivas, retardo no negativo"
                            + " y multiplicador finito >= 1 requeridos.");
        }
    }
}

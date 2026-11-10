package com.dentalclinic.appointment.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración del dispatcher de comunicaciones (F8/T1).
 *
 * <p>Ningún valor es una decisión de producto cerrada: el máximo de intentos y el backoff son
 * "configurables" (PRD §14.5/§34.12) y aquí se materializan como valores por defecto de
 * implementación sobrescribibles por entorno. Las credenciales SMTP <strong>no</strong> viven aquí:
 * llegan por variables de entorno {@code SPRING_MAIL_*}.
 *
 * @param email    configuración del adaptador SMTP
 * @param dispatch configuración del bucle de despacho
 */
@ConfigurationProperties(prefix = "dentalclinic.communication")
public record CommunicationProperties(Email email, Dispatch dispatch) {

    /** Adaptador SMTP. Sin {@code enabled=true} no existe emisor y no se envía nada. */
    public record Email(boolean enabled, String from) {
    }

    /**
     * Bucle de despacho.
     *
     * @param enabled          si el job programado está activo (por defecto, no)
     * @param batchSize        filas reclamadas por tick
     * @param maxAttempts      intentos máximos antes de pasar a FAILED
     * @param initialBackoff   espera del primer reintento
     * @param backoffMultiplier factor de crecimiento exponencial
     * @param maxBackoff       tope del backoff
     * @param lease            vigencia del claim mientras se envía por SMTP
     * @param interval         periodo entre ticks del job
     * @param initialDelay     retardo del primer tick
     */
    public record Dispatch(boolean enabled, int batchSize, int maxAttempts, Duration initialBackoff,
            double backoffMultiplier, Duration maxBackoff, Duration lease, Duration interval,
            Duration initialDelay) {

        public Dispatch {
            batchSize = batchSize <= 0 ? 20 : batchSize;
            maxAttempts = maxAttempts <= 0 ? 4 : maxAttempts;
            initialBackoff = initialBackoff == null ? Duration.ofMinutes(1) : initialBackoff;
            backoffMultiplier = backoffMultiplier <= 0 ? 5.0 : backoffMultiplier;
            maxBackoff = maxBackoff == null ? Duration.ofHours(1) : maxBackoff;
            lease = lease == null ? Duration.ofMinutes(5) : lease;
            interval = interval == null ? Duration.ofSeconds(60) : interval;
            initialDelay = initialDelay == null ? Duration.ofSeconds(30) : initialDelay;
        }
    }
}

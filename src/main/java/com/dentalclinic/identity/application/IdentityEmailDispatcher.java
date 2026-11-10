package com.dentalclinic.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.dentalclinic.appointment.application.EmailSender;
import com.dentalclinic.appointment.application.OutboundEmail;
import com.dentalclinic.identity.infrastructure.config.IdentityEmailProperties;
import com.dentalclinic.identity.infrastructure.config.IdentityLinkProperties;

/**
 * Dispatcher de la outbox de identidad (F8B.2; Architecture.md 9.1/9.2, §9.7).
 *
 * <p>Ciclo de vida de una ejecución:
 * <ol>
 *   <li>Reclama un lote de filas {@code PENDING} vigentes y elegibles con
 *       {@code FOR UPDATE SKIP LOCKED}, las reserva con un lease y cierra la transacción
 *       <strong>antes</strong> de tocar la red.</li>
 *   <li>Recompone y verifica el token ({@link IdentityEmailComposer}) y, solo si la emisión sigue
 *       vigente, envía el correo por el puerto {@link EmailSender}, fuera de toda transacción.</li>
 *   <li>Marca {@code SENT} solo si el proveedor confirmó; en caso de fallo registra el error y
 *       programa el reintento con backoff, o {@code FAILED} si se agotaron los intentos.</li>
 * </ol>
 *
 * <p><strong>Todo está apagado por defecto.</strong> Si no existe {@link EmailSender} (porque el
 * canal SMTP no está habilitado) o los enlaces de identidad no están configurados
 * ({@code IDENTITY_PUBLIC_BASE_URL} + {@code IDENTITY_LINK_SECRET} + {@code links-enabled}),
 * {@code dispatchOnce} no hace nada: las entregas permanecen {@code PENDING} y <strong>no se
 * consume ningún intento</strong>. Nunca se inventan dominios, remitentes ni credenciales.
 *
 * <p>Resiliencia: el lease viaja en {@code lease_until}; si el proceso muere entre el claim y el
 * marcado, la fila vuelve a ser elegible al vencer el lease, sin perder la entrega.
 *
 * <p>Limitación conocida y documentada (Architecture.md 9.3): el lease y los UNIQUE garantizan
 * deduplicación de filas, no entrega SMTP exactamente-una-vez. Si el proveedor acepta el envío y el
 * proceso muere antes de marcar {@code SENT}, al vencer el lease la fila se reintenta y el correo
 * puede duplicarse. FAILED conserva los fallos agotados para intervención; no garantiza recepción.
 *
 * <p>Seguridad: ni el token ni la URL completa se registran. El error persistido está acotado y
 * saneado (solo clase de excepción), nunca mensajes arbitrarios del proveedor ni el cuerpo del correo.
 */
@Service
public class IdentityEmailDispatcher {

    private static final Logger log = LoggerFactory.getLogger(IdentityEmailDispatcher.class);
    private static final int MAX_ERROR_LENGTH = 100;

    private final IdentityEmailClaimService claims;
    private final IdentityEmailComposer composer;
    private final IdentityEmailProperties properties;
    private final IdentityLinkProperties links;
    private final Optional<EmailSender> emailSender;
    private final Clock clock;

    public IdentityEmailDispatcher(IdentityEmailClaimService claims, IdentityEmailComposer composer,
            IdentityEmailProperties properties, IdentityLinkProperties links,
            Optional<EmailSender> emailSender, Clock clock) {
        this.claims = claims;
        this.composer = composer;
        this.properties = properties;
        this.links = links;
        this.emailSender = emailSender;
        this.clock = clock;
    }

    /** True si hay adaptador de canal y enlaces configurados: se puede entregar de verdad. */
    public boolean isDeliveryConfigured() {
        return emailSender.isPresent() && links.configured();
    }

    /** Procesa un lote y devuelve cuántos correos confirmó el proveedor. */
    public int dispatchOnce() {
        return dispatchOnce(clock.instant());
    }

    /** Variante determinista para pruebas/reloj inyectado. */
    public int dispatchOnce(Instant now) {
        if (!isDeliveryConfigured()) {
            return 0;
        }
        List<ClaimedIdentityEmail> batch = claims.claim(now, properties.batchSize(), properties.lease());
        int sent = 0;
        for (ClaimedIdentityEmail email : batch) {
            try {
                Optional<OutboundEmail> outbound = composer.compose(email);
                if (outbound.isEmpty()) {
                    // No invalida una reserva nueva si este snapshot ya quedó obsoleto.
                    claims.supersede(email, clock.instant());
                    continue;
                }
                emailSender.orElseThrow().send(outbound.get());
            } catch (RuntimeException ex) {
                handleFailure(email, ex, clock.instant());
                continue;
            }
            try {
                claims.markSent(email, clock.instant());
                sent++;
            } catch (RuntimeException ex) {
                // El proveedor ya aceptó: no es un fallo de envío ni justifica reintento.
                log.error("El proveedor acepto el enlace de identidad {}, pero fallo el registro: {}",
                        email.id(), ex.getClass().getSimpleName());
            }
        }
        return sent;
    }

    private void handleFailure(ClaimedIdentityEmail email, RuntimeException ex, Instant now) {
        String error = sanitize(ex);
        try {
            if (email.attempts() >= properties.maxAttempts()) {
                claims.markFailed(email, error, now);
                log.warn("Enlace de identidad {} agoto intentos ({}); marcado FAILED: {}",
                        email.id(), email.attempts(), error);
                return;
            }
            Duration backoff = backoffFor(email.attempts());
            claims.scheduleRetry(email, error, now.plus(backoff), now);
            log.warn("Fallo de envio de enlace de identidad {} (intento {}/{}); reintento en {}: {}",
                    email.id(), email.attempts(), properties.maxAttempts(), backoff, error);
        } catch (RuntimeException persistenceFailure) {
            // El lease permite recuperar la entrega; nunca se pierde por un fallo de registro.
            log.error("Fallo el registro del error del enlace de identidad {}: {}",
                    email.id(), persistenceFailure.getClass().getSimpleName());
        }
    }

    private Duration backoffFor(int attempt) {
        double factor = Math.pow(properties.backoffMultiplier(), Math.max(0, attempt - 1));
        Duration delay = Duration.ofMillis((long) (properties.initialBackoff().toMillis() * factor));
        return delay.compareTo(properties.maxBackoff()) > 0 ? properties.maxBackoff() : delay;
    }

    /** No copia getMessage/toString/causas: pueden contener el enlace, secreto o cuerpo completos. */
    private static String sanitize(RuntimeException ex) {
        String message = ex.getClass().getSimpleName();
        if (message.isEmpty()) {
            message = "EmailDeliveryFailure";
        }
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }
}

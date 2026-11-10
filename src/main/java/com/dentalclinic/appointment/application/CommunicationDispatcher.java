package com.dentalclinic.appointment.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.dentalclinic.appointment.infrastructure.config.CommunicationProperties;
import com.dentalclinic.appointment.domain.CommunicationType;

/**
 * Dispatcher de la outbox de email (F8/T1; Architecture.md 9.1/9.2, PRD §14/§34.12-13).
 *
 * <p>Ciclo de vida de una ejecución:
 * <ol>
 *   <li>Reclama un lote de filas {@code PENDING} elegibles con {@code FOR UPDATE SKIP LOCKED},
 *       las reserva con un lease y cierra la transacción <strong>antes</strong> de tocar la red.</li>
 *   <li>Envía cada correo por el puerto {@link EmailSender} (SMTP real o emisor falso en tests),
 *       fuera de cualquier transacción.</li>
 *   <li>Marca {@code SENT} solo si el proveedor confirmó; en caso de fallo registra el error y
 *       programa el reintento con backoff, o {@code FAILED} si se agotaron los intentos.</li>
 * </ol>
 *
 * <p><strong>Sin SMTP configurado no envía ni marca nada.</strong> Si no existe {@link EmailSender}
 * (porque {@code dentalclinic.communication.email.enabled} es falso), {@code dispatchOnce} no hace
 * nada: los mensajes permanecen {@code PENDING} y no se consume ningún intento. Un envío simulado o
 * deshabilitado nunca marca {@code SENT}.
 *
 * <p>Resiliencia: el lease viaja en {@code next_retry_at}; si el proceso muere entre el claim y el
 * marcado, la fila seguirá {@code PENDING} y volverá a ser elegible al vencer el lease, sin perder
 * el mensaje.
 *
 * <p>Limitación conocida y documentada (Architecture.md 9.3): el lease y el UNIQUE local garantizan
 * deduplicación de filas, no entrega SMTP exactamente-una-vez ante aceptación externa seguida de
 * fallo al marcar {@code SENT}.
 */
@Service
public class CommunicationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CommunicationDispatcher.class);
    private static final int MAX_ERROR_LENGTH = 100;

    private final CommunicationClaimService claims;
    private final CommunicationEmailComposer composer;
    private final CommunicationProperties properties;
    private final Optional<EmailSender> emailSender;
    private final Clock clock;
    private final com.dentalclinic.feedback.application.FeedbackService feedback;

    public CommunicationDispatcher(CommunicationClaimService claims,
            CommunicationEmailComposer composer, CommunicationProperties properties,
            Optional<EmailSender> emailSender, Clock clock,
            com.dentalclinic.feedback.application.FeedbackService feedback) {
        this.claims = claims;
        this.composer = composer;
        this.properties = properties;
        this.emailSender = emailSender;
        this.clock = clock;
        this.feedback = feedback;
    }

    /** True si hay un adaptador de canal disponible para enviar de verdad. */
    public boolean isDeliveryConfigured() {
        return emailSender.isPresent();
    }

    /**
     * Procesa un lote y devuelve cuántos correos confirmó el proveedor. Es seguro invocarlo desde
     * varias instancias a la vez: el claim atómico reparte las filas sin solaparse.
     */
    public int dispatchOnce() {
        return dispatchOnce(clock.instant());
    }

    /** Variante determinista para pruebas/reloj inyectado. */
    public int dispatchOnce(Instant now) {
        if (emailSender.isEmpty()) {
            return 0;
        }
        CommunicationProperties.Dispatch dispatch = properties.dispatch();
        return dispatch(now, dispatch.batchSize(), dispatch.lease());
    }

    private int dispatch(Instant now, int batchSize, Duration lease) {
        List<ClaimedEmail> batch = claims.claim(now, batchSize, lease);
        int sent = 0;
        for (ClaimedEmail email : batch) {
            if (email.type() == CommunicationType.REMINDER) {
                sent += dispatchReminder(email);
                continue;
            }
            sent += dispatchNotice(email);
        }
        return sent;
    }

    private int dispatchNotice(ClaimedEmail email) {
        try {
            if (!feedbackCurrent(email)) {
                claims.supersede(email, clock.instant());
                return 0;
            }
            if (!claims.prepareSend(email)) {
                return 0;
            }
            OutboundEmail outbound = composer.compose(email);
            // Componer no mantiene locks durante la lectura; volver a cercar estado/versión/lease.
            if (!claims.prepareSend(email)) {
                return 0;
            }
            if (!feedbackCurrent(email)) {
                claims.supersede(email, clock.instant());
                return 0;
            }
            emailSender.orElseThrow().send(outbound);
        } catch (RuntimeException ex) {
            try {
                handleFailure(email, ex, clock.instant());
            } catch (RuntimeException persistenceFailure) {
                log.error("Fallo el registro del error de comunicacion {}: {}",
                        email.id(), persistenceFailure.getClass().getSimpleName());
            }
            return 0;
        }
        try {
            if (!claims.markSent(email, clock.instant())) {
                log.warn("SMTP acepto comunicacion {}, pero la reserva ya cambió", email.id());
            }
        } catch (RuntimeException ex) {
            // Aceptación externa: nunca tratar un fallo de persistencia como fallo del envío.
            log.error("SMTP acepto comunicacion {}, pero fallo el registro: {}",
                    email.id(), ex.getClass().getSimpleName());
        }
        return 1;
    }

    private boolean feedbackCurrent(ClaimedEmail email) {
        return email.type() != CommunicationType.FEEDBACK_REQUESTED || feedback.sendable(email.appointmentId());
    }

    private int dispatchReminder(ClaimedEmail reserved) {
        OutboundEmail outbound;
        ClaimedEmail sending;
        try {
            outbound = composer.compose(reserved);
            Optional<ClaimedEmail> prepared = claims.prepareReminderSend(reserved);
            if (prepared.isEmpty()) {
                return 0;
            }
            sending = prepared.orElseThrow();
        } catch (RuntimeException ex) {
            log.warn("No se pudo preparar REMINDER {}: {}", reserved.id(), ex.getClass().getSimpleName());
            return 0; // No hubo intento SMTP; el lease permite recuperar una preparación fallida.
        }
        try {
            emailSender.orElseThrow().send(outbound);
        } catch (RuntimeException ex) {
            Instant now = clock.instant();
            try {
                claims.recordReminderFailure(sending, sanitize(ex), now.plus(backoffFor(sending.attempts())),
                        now);
            } catch (RuntimeException persistenceFailure) {
                log.error("Fallo el registro del error de REMINDER {}: {}",
                        sending.id(), persistenceFailure.getClass().getSimpleName());
            }
            return 0;
        }
        try {
            claims.recordReminderSent(sending, clock.instant());
        } catch (RuntimeException ex) {
            // SMTP ya aceptó. Un fallo de persistencia no es un fallo SMTP ni justifica cambiar a FAILED.
            log.error("SMTP acepto REMINDER {}, pero fallo el registro del resultado: {}",
                    sending.id(), ex.getClass().getSimpleName());
        }
        return 1;
    }

    private void handleFailure(ClaimedEmail email, RuntimeException ex, Instant now) {
        // Excepciones de proveedor pueden incluir el cuerpo/URL. Nunca guardar secretos de feedback.
        String error = email.type() == CommunicationType.FEEDBACK_REQUESTED
                ? "No se pudo entregar la invitación de feedback." : sanitize(ex);
        if (email.attempts() >= properties.dispatch().maxAttempts()) {
            if (claims.markFailed(email, error, now)) {
                log.warn("Comunicacion {} agoto intentos ({}); marcada FAILED: {}",
                        email.id(), email.attempts(), error);
            }
            return;
        }
        Duration backoff = backoffFor(email.attempts());
        if (claims.scheduleRetry(email, error, now.plus(backoff), now)) {
            log.warn("Fallo de envio de comunicacion {} (intento {}/{}); reintento en {}: {}",
                    email.id(), email.attempts(), properties.dispatch().maxAttempts(), backoff, error);
        }
    }

    private Duration backoffFor(int attempt) {
        CommunicationProperties.Dispatch dispatch = properties.dispatch();
        double factor = Math.pow(dispatch.backoffMultiplier(), Math.max(0, attempt - 1));
        Duration delay = Duration.ofMillis((long) (dispatch.initialBackoff().toMillis() * factor));
        return delay.compareTo(dispatch.maxBackoff()) > 0 ? dispatch.maxBackoff() : delay;
    }

    /** Mensajes/toString/causas del proveedor pueden incluir cuerpo, destinatario o enlace privado. */
    private static String sanitize(RuntimeException ex) {
        String message = ex.getClass().getSimpleName();
        if (message.isEmpty()) {
            message = "EmailDeliveryFailure";
        }
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }
}

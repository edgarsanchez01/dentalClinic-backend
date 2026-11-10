package com.dentalclinic.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.appointment.application.OutboundEmail;
import com.dentalclinic.identity.domain.IdentityEmailPurpose;
import com.dentalclinic.identity.domain.IdentityEmailStatus;
import com.dentalclinic.identity.infrastructure.config.IdentityLinkProperties;
import com.dentalclinic.identity.infrastructure.repository.InvitationRepository;
import com.dentalclinic.identity.infrastructure.repository.IdentityEmailOutboxRepository;
import com.dentalclinic.identity.infrastructure.repository.PasswordResetTokenRepository;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;
import com.dentalclinic.identity.infrastructure.token.IdentityLinkTokens;

/**
 * Compone el correo de un enlace de identidad en el momento del envío (F8B.2, §9.7).
 *
 * <p>No hay token guardado en claro: se <strong>recompone</strong> de forma determinista desde la
 * terna inmutable {@code (purpose, userId, nonce)} con {@link IdentityLinkTokens} y se verifica que
 * su SHA-256 coincide con {@link ClaimedIdentityEmail#tokenHash()}. Después se comprueba que la
 * emisión original <strong>sigue vigente</strong> —no consumida, no expirada y no revocada— en
 * {@code invitations}/{@code password_reset_tokens}. Si cualquiera de las comprobaciones falla, se
 * devuelve vacío y el dispatcher invalida la entrega: <strong>un token obsoleto nunca se envía</strong>.
 *
 * <p>La transacción es de solo lectura y termina antes de que el dispatcher abra SMTP.
 *
 * <p><strong>Rotación de la clave HMAC.</strong> Si {@code IDENTITY_LINK_SECRET} cambia mientras hay
 * entregas {@code PENDING}, el token recompuesto con la clave nueva ya no coincide con el hash de la
 * emisión antigua: el compositor detecta el desajuste y devuelve vacío, de modo que el enlace roto
 * <em>no</em> se envía (la entrega queda invalidada). Por eso la clave debe rotarse solo tras drenar
 * o invalidar las entregas pendientes; rotar antes de tiempo no entrega enlaces corruptos, pero
 * descarta esas invitaciones/recuperaciones, que deberán reemitirse.
 *
 * <p>Seguridad: ni el token ni la URL completa se registran ni se persisten; solo viven en el
 * {@link OutboundEmail} efímero que consume el adaptador de envío.
 */
@Component
public class IdentityEmailComposer {

    private final IdentityLinkTokens tokens;
    private final IdentityLinkProperties links;
    private final InvitationRepository invitations;
    private final PasswordResetTokenRepository passwordResets;
    private final IdentityEmailOutboxRepository outbox;
    private final UserRepository users;
    private final Clock clock;

    public IdentityEmailComposer(IdentityLinkTokens tokens, IdentityLinkProperties links,
            InvitationRepository invitations, PasswordResetTokenRepository passwordResets,
            IdentityEmailOutboxRepository outbox, UserRepository users, Clock clock) {
        this.tokens = tokens;
        this.links = links;
        this.invitations = invitations;
        this.passwordResets = passwordResets;
        this.outbox = outbox;
        this.users = users;
        this.clock = clock;
    }

    /** Devuelve el correo listo para enviar, o vacío si la emisión ya no es entregable. */
    @Transactional(readOnly = true)
    public Optional<OutboundEmail> compose(ClaimedIdentityEmail claimed) {
        if (!links.configured()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        // El snapshot pudo quedar obsoleto después del claim (reemisión o recuperación del lease).
        if (outbox.findById(claimed.id())
                .filter(row -> row.getStatus() == IdentityEmailStatus.PENDING)
                .filter(row -> row.getSupersededAt() == null)
                .filter(row -> row.ownsReservation(claimed.attempts(), claimed.leaseUntil()))
                .filter(row -> row.getLeaseUntil() != null && row.getLeaseUntil().isAfter(now))
                .isEmpty()) {
            return Optional.empty();
        }
        if (users.findById(claimed.userId())
                .filter(user -> user.getEmail().equals(claimed.recipientEmail()))
                .filter(user -> claimed.purpose() != IdentityEmailPurpose.PASSWORD_RESET || user.isActive())
                .isEmpty()) {
            return Optional.empty();
        }
        String token;
        try {
            token = tokens.token(domainOf(claimed.purpose()), claimed.userId(), claimed.tokenNonce());
        } catch (RuntimeException ex) {
            // Sin clave utilizable o nonce inválido: no se puede recomponer; no se envía nada.
            return Optional.empty();
        }
        if (!tokens.hash(token).equals(claimed.tokenHash())) {
            // Clave rotada o fila manipulada: el enlace recomponible no es el emitido.
            return Optional.empty();
        }
        if (!emissionUsable(claimed, now)) {
            return Optional.empty();
        }
        return Optional.of(render(claimed, token));
    }

    /** La emisión sigue vigente si el token no fue consumido, no expiró ni fue revocado (reemitido). */
    private boolean emissionUsable(ClaimedIdentityEmail claimed, Instant now) {
        return switch (claimed.purpose()) {
            case STAFF_INVITATION -> invitations.findByTokenHash(claimed.tokenHash())
                    .filter(invitation -> invitation.isUsableAt(now))
                    .filter(invitation -> invitation.getEmail().equals(claimed.recipientEmail()))
                    .filter(invitation -> invitation.getUserId()
                            .map(userId -> userId.equals(claimed.userId())).orElse(false))
                    .isPresent();
            case PASSWORD_RESET -> passwordResets.findByTokenHash(claimed.tokenHash())
                    .filter(reset -> reset.isUsableAt(now))
                    .filter(reset -> reset.getUserId().equals(claimed.userId()))
                    .isPresent();
        };
    }

    private OutboundEmail render(ClaimedIdentityEmail claimed, String token) {
        String url = links.publicBaseUrl() + linkPath(claimed.purpose()) + token;
        return switch (claimed.purpose()) {
            case STAFF_INVITATION -> new OutboundEmail(claimed.recipientEmail(),
                    "Activa tu cuenta de DentalClinic", activationBody(url));
            case PASSWORD_RESET -> new OutboundEmail(claimed.recipientEmail(),
                    "Restablece tu contraseña", resetBody(url));
        };
    }

    private static String linkPath(IdentityEmailPurpose purpose) {
        return switch (purpose) {
            case STAFF_INVITATION -> "/activate?token=";
            case PASSWORD_RESET -> "/reset-password?token=";
        };
    }

    private static IdentityLinkTokens.Domain domainOf(IdentityEmailPurpose purpose) {
        return switch (purpose) {
            case STAFF_INVITATION -> IdentityLinkTokens.Domain.STAFF_INVITATION;
            case PASSWORD_RESET -> IdentityLinkTokens.Domain.PASSWORD_RESET;
        };
    }

    private static String activationBody(String url) {
        return "Hola,\n\n"
                + "Tu cuenta de DentalClinic está lista para activarse. Abre el siguiente enlace para "
                + "definir tu contraseña y acceder:\n\n"
                + url + "\n\n"
                + "El enlace es personal, de un solo uso y caduca. No lo compartas.\n"
                + "Si no esperabas esta invitación, puedes ignorar este mensaje.\n\n"
                + "DentalClinic";
    }

    private static String resetBody(String url) {
        return "Hola,\n\n"
                + "Recibimos una solicitud para restablecer tu contraseña de DentalClinic. Abre el "
                + "siguiente enlace para elegir una nueva:\n\n"
                + url + "\n\n"
                + "El enlace es personal, de un solo uso y caduca. No lo compartas.\n"
                + "Si no solicitaste el cambio, ignora este mensaje: tu contraseña actual seguirá "
                + "siendo válida.\n\n"
                + "DentalClinic";
    }
}

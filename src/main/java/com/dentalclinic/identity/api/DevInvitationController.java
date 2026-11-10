package com.dentalclinic.identity.api;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dentalclinic.identity.api.dto.ResentInvitationDto;
import com.dentalclinic.identity.application.StaffInvitationService;
import com.dentalclinic.identity.application.StaffInvitationService.ResentInvitation;
import com.dentalclinic.identity.application.IdentityRequestLimiter;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.infrastructure.security.IdentityClientAddress;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Reenvío de invitaciones de activación — <strong>solo perfil {@code dev}</strong>.
 *
 * <p>Se conserva el mecanismo local anterior al transporte 8B.3. {@code @Profile("dev & !prod")} garantiza que la ruta
 * <strong>no existe en producción</strong>: en cualquier otro perfil responde 404, y la matriz §7.4
 * fila "Users" sigue exigiendo OWNER ({@code /api/users/**} es READ_WRITE solo para OWNER).
 *
 * <p>El token se devuelve una única vez en la respuesta; su hash es lo único que se persiste.
 * Protecciones heredadas del dominio: token de un solo uso, expirable y con rol fijado por la
 * cuenta (P-07 sigue bloqueando OWNER).
 */
@Profile("dev & !prod")
@RestController
@RequestMapping("/api/users")
public class DevInvitationController {

    private final StaffInvitationService staffInvitationService;
    private final IdentityRequestLimiter limiter;
    private final IdentityClientAddress clientAddress;

    public DevInvitationController(StaffInvitationService staffInvitationService,
            IdentityRequestLimiter limiter, IdentityClientAddress clientAddress) {
        this.staffInvitationService = staffInvitationService;
        this.limiter = limiter;
        this.clientAddress = clientAddress;
    }

    @PostMapping("/{userId}/invitations/resend")
    public ResentInvitationDto resend(@PathVariable Long userId, HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        if (!limiter.allowStaffIp(clientAddress.resolve(request))) {
            throw new ForbiddenOperationException("Espera antes de volver a solicitar la invitación.");
        }
        ResentInvitation invitation = staffInvitationService.reissueInvitation(userId);
        return new ResentInvitationDto(userId, invitation.email(), invitation.rawToken());
    }
}

package com.dentalclinic.identity.api.dto;

/**
 * Invitación reemitida (solo perfil {@code dev}).
 *
 * <p>Respuesta de {@code POST /api/users/{userId}/invitations/resend}: el {@code token} en claro se
 * entregó <strong>una única vez</strong> al OWNER, que construye el enlace de activación. Nunca se
 * persiste en claro (solo su hash SHA-256) ni se registra en logs.
 */
public record ResentInvitationDto(Long userId, String email, String token) {
    @Override
    public String toString() {
        return "ResentInvitationDto{userId=" + userId + ", email=<redacted>, token=<redacted>}";
    }
}

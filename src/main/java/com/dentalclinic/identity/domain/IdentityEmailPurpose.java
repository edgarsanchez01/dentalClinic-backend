package com.dentalclinic.identity.domain;

/**
 * Uso de un enlace de identidad (F8B.2). Cada valor corresponde a un
 * {@link com.dentalclinic.identity.infrastructure.token.IdentityLinkTokens.Domain} distinto, de
 * modo que un token de invitación jamás es válido como token de recuperación.
 *
 * <p>Se persiste como cadena (V018 lo limita por CHECK a estos dos valores).
 */
public enum IdentityEmailPurpose {

    /** Invitación de activación de personal (RF-IA-09, PRD §6.5.2-A). Enlace {@code /activate}. */
    STAFF_INVITATION,

    /** Recuperación de contraseña de cualquier cuenta activa (RF-IA-12). Enlace {@code /reset-password}. */
    PASSWORD_RESET
}

package com.dentalclinic.identity.infrastructure.config;

import java.net.URI;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración de la entrega de enlaces de identidad (F8B/T1).
 *
 * <p>H-A02 (Architecture.md §21) describe el hueco: la invitación de personal y la recuperación de
 * contraseña emiten un token de un solo uso pero <strong>descartan el valor en claro</strong> —solo
 * persisten su hash— y hoy no existe canal que lo entregue. Esta configuración agrupa lo mínimo que
 * necesita ese canal: la base pública del frontend y una clave HMAC <strong>dedicada</strong> con la
 * que recomponer el enlace en el momento del envío (patrón de §9.7), sin persistir jamás el token ni
 * la URL.
 *
 * <p><strong>Sin fallback a {@code JWT_SECRET}.</strong> A diferencia de feedback (§9.7), la clave
 * aquí no hereda del secreto de firma de sesión: son dominios criptográficos distintos y reutilizar
 * la clave de sesión permitiría que un enlace filtrado comprometiera la firma de tokens de acceso.
 * Si no hay clave dedicada, el componente de tokens se declara no configurado y no emite.
 *
 * <p><strong>Todo es apagado por defecto.</strong> La funcionalidad permanece inerte mientras
 * {@code links-enabled} sea {@code false} (o su valor por defecto), igual que el dispatcher de
 * comunicaciones y el productor de recordatorios. En ese estado ni la URL ni la clave son
 * obligatorias: el arranque de los entornos actuales no cambia.
 *
 * @param publicBaseUrl origen/base pública del frontend (HTTPS; HTTP solo localhost/127.0.0.1).
 *     Se normaliza sin barras finales.
 * @param linkSecret    clave HMAC exclusiva de los enlaces de identidad, de al menos 32 caracteres.
 *     Nunca se registra ni se persiste.
 * @param linksEnabled  interruptor operativo explícito; al habilitarlo exige URL y clave válidas.
 */
@ConfigurationProperties(prefix = "dentalclinic.identity")
public record IdentityLinkProperties(String publicBaseUrl, String linkSecret, boolean linksEnabled) {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1");
    private static final int MIN_SECRET_LENGTH = 32;

    public IdentityLinkProperties {
        publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.strip().replaceAll("/+$", "");
        linkSecret = linkSecret == null ? "" : linkSecret;

        if (!publicBaseUrl.isEmpty()) {
            validateUrl(publicBaseUrl);
        }
        if (!linkSecret.isEmpty() && linkSecret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException(
                    "La clave de enlaces de identidad requiere al menos " + MIN_SECRET_LENGTH + " caracteres.");
        }
        if (linksEnabled) {
            if (publicBaseUrl.isEmpty()) {
                throw new IllegalArgumentException(
                        "IDENTITY_PUBLIC_BASE_URL es obligatoria al habilitar los enlaces de identidad.");
            }
            if (linkSecret.isEmpty()) {
                throw new IllegalArgumentException(
                        "IDENTITY_LINK_SECRET es obligatoria al habilitar los enlaces de identidad.");
            }
        }
    }

    private static void validateUrl(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            // URI incluye el valor de entrada en su excepción; no propagar una URL potencialmente sensible.
            throw new IllegalArgumentException("La URL pública de identidad no es válida.");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null
                || !("https".equals(uri.getScheme())
                        || ("http".equals(uri.getScheme()) && LOCAL_HOSTS.contains(uri.getHost())))) {
            throw new IllegalArgumentException(
                    "La URL pública de identidad debe usar HTTPS (HTTP solo localhost).");
        }
    }

    /** {@code true} si hay una clave HMAC dedicada con la que recomponer enlaces. */
    public boolean hasLinkSecret() {
        return !linkSecret.isEmpty();
    }

    /** {@code true} si el canal de enlaces está operativo (interruptor activo y material presente). */
    public boolean configured() {
        return linksEnabled && !publicBaseUrl.isEmpty() && hasLinkSecret();
    }

    /** Redacta la clave: nunca debe aparecer en logs. */
    @Override
    public String toString() {
        return "IdentityLinkProperties{publicBaseUrl=" + publicBaseUrl
                + ", linkSecret=" + (linkSecret.isEmpty() ? "<empty>" : "<redacted>")
                + ", linksEnabled=" + linksEnabled + "}";
    }
}

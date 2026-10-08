package com.dentalclinic.identity.infrastructure.security;

import java.time.Duration;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Emisión y retirada de la cookie del refresh token.
 *
 * <p>Los cuatro atributos son <strong>normativos</strong> (P-01 CLOSED, §7.1): {@code HttpOnly},
 * {@code Secure}, {@code SameSite=Lax} y {@code Path=/api/auth}. El único que varía es
 * {@code Secure}, y solo por una razón técnica inevitable: en dev y test el transporte es HTTP sin
 * TLS, y un navegador <strong>descartaría</strong> una cookie {@code Secure} en claro, con lo que
 * el flujo de refresh no se podría ni probar. En producción el valor es {@code true} y la
 * propiedad no admite otra cosa en la práctica.
 *
 * <p>{@code Path} restringido a {@code /api/auth} es lo que impide que el JavaScript de la página
 * lea la cookie desde otras rutas del sitio (mitigación de §7.1), y también significa que el
 * navegador no la envía al resto de la API: los endpoints ajenos a {@code /api/auth} no reciben
 * nunca la cookie.
 */
@Component
public class RefreshCookieFactory {

    private static final String PATH_PREFIX = "/api/auth";
    private static final String SAME_SITE = "Lax";

    private final SecurityProperties properties;

    public RefreshCookieFactory(SecurityProperties properties) {
        this.properties = properties;
        if (!PATH_PREFIX.equals(properties.refreshCookie().path())) {
            throw new IllegalStateException(
                    "dentalclinic.security.refresh-cookie.path debe ser " + PATH_PREFIX
                            + " (P-01 CLOSED, Architecture.md §7.1)");
        }
        if (!SAME_SITE.equalsIgnoreCase(properties.refreshCookie().sameSite())) {
            throw new IllegalStateException(
                    "dentalclinic.security.refresh-cookie.same-site debe ser Lax (P-01 CLOSED)");
        }
    }

    /** Cookie de vida corta para el refresh token emitido o rotado. */
    public ResponseCookie issue(String token, Duration ttl) {
        return base(token).maxAge(ttl).build();
    }

    /**
     * Cookie que caduca de inmediato en el navegador.
     *
     * <p>Se usa al hacer logout y al detectar reutilización. No basta con revocar en base de datos:
     * la fila queda invalidada para el servidor, pero el token sigue en el navegador hasta que
     * expire la cookie.
     */
    public ResponseCookie expire() {
        return base("").maxAge(Duration.ZERO).build();
    }

    /** Extrae el refresh token de la cookie, o vacío si no viaja. */
    public String read(jakarta.servlet.http.Cookie[] cookies) {
        if (cookies == null) {
            return null;
        }
        for (jakarta.servlet.http.Cookie cookie : cookies) {
            if (properties.refreshCookie().name().equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private ResponseCookie.ResponseCookieBuilder base(String token) {
        SecurityProperties.RefreshCookie cookie = properties.refreshCookie();
        return ResponseCookie.from(cookie.name(), token)
                .httpOnly(true)
                .secure(cookie.secure())
                .path(cookie.path())
                .sameSite(cookie.sameSite());
    }
}
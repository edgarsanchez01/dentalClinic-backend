package com.dentalclinic.identity.infrastructure.security;

import java.time.Duration;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración de seguridad de Fase 2.
 *
 * <p><strong>AD-23 (Architecture.md §21):</strong> "el <em>algoritmo de firma</em> del access
 * token y la <em>gestión de claves</em> son una decisión de <em>implementación</em> de seguridad,
 * no de producto. Ningún algoritmo concreto está aprobado por P-01/P-02." Lo mismo declara P-02
 * sobre "el algoritmo de hash y la estructura concreta de la tabla de sesiones".
 *
 * <p>Por tanto, aquí hay dos cosas deliberadamente distintas:
 *
 * <ul>
 *   <li><strong>Normativo</strong> y no negociable: access token de 15 minutos, refresh de 30 días
 *       con ventana deslizante, cookie {@code HttpOnly}/{@code Secure}/{@code SameSite=Lax}/
 *       {@code Path=/api/auth} (P-01 y P-02 CLOSED, Architecture.md §7.1).</li>
 *   <li><strong>Elección de implementación</strong>, documentada aquí y en el reporte de fase:
 *       firma {@code HS256} con clave simétrica tomada del entorno (AD-23), y SHA-256 para los
 *       tokens opacos.</li>
 * </ul>
 *
 * <p><strong>Caducidades pendientes de decisión de producto.</strong> La vida del token de
 * invitación/activación y la del token de recuperación de contraseña <strong>no están definidas
 * en ningún documento normativo</strong>: RF-IA-09 y RF-IA-12 solo exigen que expiren. Por eso son
 * propiedades <strong>obligatorias sin valor por defecto</strong>: si el entorno no las define, el
 * arranque falla de forma clara en lugar de asumir 72 h, 1 h o los 30 días del refresh. Los
 * perfiles dev y test fijan valores exclusivamente técnicos para poder ejecutar y probar, y no
 * constituyen ni una decisión de producto ni un default de producción.
 */
@Validated
@ConfigurationProperties(prefix = "dentalclinic.security")
public record SecurityProperties(

        @Valid @NotNull Jwt jwt,
        @Valid @NotNull Refresh refresh,
        @Valid @NotNull OneTimeToken invitation,
        @Valid @NotNull OneTimeToken passwordReset,
        @Valid @NotNull RefreshCookie refreshCookie,
        @Valid @NotNull Cors cors) {

    /**
     * @param secret clave simétrica de firma. HS256 de jjwt exige al menos 256 bits; se
     *     valida con 32 caracteres para que un valor débil no pase inadvertido.
     * @param accessTokenTtl 15 minutos. Valor <strong>normativo</strong> (§7.1).
     */
    public record Jwt(
            @NotBlank @Size(min = 32, message = "la clave de firma debe tener al menos 32 caracteres")
            String secret,
            @NotBlank String issuer,
            @NotNull Duration accessTokenTtl) {
    }

    /**
     * 30 días con ventana deslizante en cada rotación. Valor <strong>normativo</strong>
     * (P-02 CLOSED, §7.1). Las duraciones de los demás tokens <em>no</em> se derivan de aquí.
     */
    public record Refresh(@NotNull Duration ttl) {
    }

    /**
     * Caducidad de un token de un solo uso (invitación/activación o recuperación).
     * Sin valor por defecto en ningún perfil de producción.
     */
    public record OneTimeToken(@NotNull Duration ttl) {
    }

    /**
     * Cookie del refresh token. Los cuatro atributos son <strong>normativos</strong>
     * (P-01 CLOSED, §7.1) y no son configurables en su valor.
     *
     * @param secure el único atributo con variante por entorno: en producción es siempre
     *     {@code true}. Solo se permite desactivarlo en dev y test, donde el transporte es
     *     HTTP sin TLS y un navegador descartaría la cookie.
     */
    public record RefreshCookie(
            @NotBlank String name,
            boolean secure,
            @NotBlank String sameSite,
            @NotBlank String path) {
    }

    /**
     * CORS explícito (§7.9): orígenes permitidos del frontend, nunca {@code *} en producción.
     */
    public record Cors(@NotEmpty List<@NotBlank String> allowedOrigins) {
    }
}
package com.dentalclinic.identity.infrastructure.token;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.dentalclinic.identity.infrastructure.config.IdentityLinkProperties;

/**
 * Tokens de enlace de identidad: invitación de personal y recuperación de contraseña (F8B/T1).
 *
 * <p>El token es un valor <strong>reponible</strong> —se puede recomponer sin haberlo guardado en
 * claro— derivado por HMAC-SHA256 de datos inmutables y autorizados más un nonce único por emisión.
 * Eso cierra el hueco H-A02 (Architecture.md §21): hoy la emisión descarta el token en claro
 * ({@code StaffInvitationService.issueInvitation}, {@code PasswordService.requestReset}) y solo
 * persiste su SHA-256, de modo que ningún canal puede volver a construir el enlace. Con este
 * componente, el emisor guarda el hash (como siempre) y el dato de reconstrucción se conserva en el
 * transporte (la futura outbox de identidad, §9.7), que solo materializa el token en el momento del
 * envío. El token <strong>nunca</strong> viaja en claro más allá del envío ni se persiste.
 *
 * <h2>Contrato de reconstrucción</h2>
 *
 * <p>El token de una emisión se determina exactamente por la terna inmutable
 * {@code (domain, userId, nonce)}. {@code domain} separa criptográficamente los dos usos
 * ({@link Domain#STAFF_INVITATION}, {@link Domain#PASSWORD_RESET}) para que un token de un flujo
 * jamás valga en el otro; {@code userId} es la cuenta destino, que no cambia; y {@code nonce} es
 * aleatorio de 256 bits y <strong>único por emisión</strong>, no derivado solo del usuario, de modo
 * que dos emisiones para la misma cuenta producen tokens distintos. El nonce no se guarda en las
 * tablas de emisión —allí solo vive el hash— sino junto a la referencia de emisión en la outbox.
 * Reemitir crea una emisión nueva (nonce nuevo, token nuevo) y retira la anterior, preservando el
 * "un solo uso" que ya garantiza la base de datos.
 *
 * <h2>Por qué HMAC y no un token aleatorio (como en Fase 2)</h2>
 *
 * <p>Fase 2 usaba {@link TokenHasher#generateOpaqueToken()} (256 bits aleatorios): es seguro, pero
 * <em>irreproducible</em>. Recomponer el enlace exige una función determinista con clave externa,
 * igual que feedback (§9.7). La clave es dedicada ({@link IdentityLinkProperties#linkSecret()}) y
 * no hereda de {@code JWT_SECRET}: dominios criptográficos separados.
 *
 * <p><strong>Compatibilidad.</strong> Los tokens emitidos por Fase 2 siguen siendo válidos: el
 * consumo no cambia —busca por {@code SHA-256(token)} en la fila— y este componente no altera esa
 * ruta. El nuevo token tiene la misma forma de 43 caracteres Base64 URL-safe que
 * {@code generateOpaqueToken()}, así que también pasa cualquier validación de forma existente.
 */
@Component
public class IdentityLinkTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Pattern NONCE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final int NONCE_BYTES = 32;

    /** Dominio criptográfico del enlace. El valor de cable nunca cambia una vez emitido. */
    public enum Domain {
        STAFF_INVITATION("DentalClinic:staff-invitation:v1"),
        PASSWORD_RESET("DentalClinic:password-reset:v1");

        private final String wire;

        Domain(String wire) {
            this.wire = wire;
        }
    }

    private final IdentityLinkProperties properties;
    private final TokenHasher hasher;

    public IdentityLinkTokens(IdentityLinkProperties properties, TokenHasher hasher) {
        this.properties = properties;
        this.hasher = hasher;
    }

    /** {@code true} si hay una clave HMAC dedicada y, por tanto, se pueden emitir y recomponer tokens. */
    public boolean configured() {
        return properties.hasLinkSecret();
    }

    /** Nonce de emisión: 256 bits aleatorios en Base64 URL-safe sin padding (43 caracteres). */
    public String newNonce() {
        byte[] bytes = new byte[NONCE_BYTES];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    /**
     * Emite un token nuevo para una cuenta: genera un nonce fresco y devuelve el token y el nonce.
     *
     * <p>El llamante persiste {@link #hash(String)} del token (solo el hash, como hoy) y conserva el
     * nonce en el transporte para poder recomponer el enlace en el envío. Ni el token ni el nonce se
     * registran.
     */
    public IssuedLinkToken issue(Domain domain, long userId) {
        String nonce = newNonce();
        return new IssuedLinkToken(token(domain, userId, nonce), nonce);
    }

    /**
     * Recompone el token de una emisión a partir de su terna inmutable {@code (domain, userId, nonce)}.
     *
     * <p>Es determinista: la misma terna produce siempre el mismo token. Es la operación que usará
     * el envío para materializar el enlace sin haber guardado el token en claro.
     *
     * @throws IllegalStateException si no hay clave HMAC configurada (nunca se recurre a un fallback)
     * @throws IllegalArgumentException si {@code userId} no es positivo o el nonce no tiene la forma
     *     producida por {@link #newNonce()}
     */
    public String token(Domain domain, long userId, String nonce) {
        requireSecret();
        if (userId <= 0) {
            throw new IllegalArgumentException("El token de identidad requiere una cuenta válida.");
        }
        if (nonce == null || !NONCE_PATTERN.matcher(nonce).matches()) {
            throw new IllegalArgumentException("El nonce del token de identidad no es válido.");
        }
        String message = domain.wire + ":" + userId + ":" + nonce;
        return hmac(message);
    }

    /** SHA-256 hexadecimal (64 caracteres): lo único que se persiste de un token, nunca el token. */
    public String hash(String token) {
        return hasher.hash(token);
    }

    private void requireSecret() {
        if (!properties.hasLinkSecret()) {
            throw new IllegalStateException(
                    "No hay clave de enlaces de identidad configurada; usa IDENTITY_LINK_SECRET.");
        }
    }

    private String hmac(String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.linkSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return URL_ENCODER.encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo preparar el enlace de identidad.", e);
        }
    }

    /**
     * Resultado de {@link #issue(Domain, long)}: token en claro (efímero, solo para el envío) y nonce
     * de reconstrucción.
     */
    public record IssuedLinkToken(String token, String nonce) {

        /** No filtra el token ni el nonce en trazas. */
        @Override
        public String toString() {
            return "IssuedLinkToken{token=<redacted>, nonce=<redacted>}";
        }
    }
}

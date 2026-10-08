package com.dentalclinic.identity.infrastructure.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

/**
 * Tokens opacos de alta entropía: invitación, activación, recuperación de contraseña y
 * refresh.
 *
 * <p>Architecture.md §7.7: "Tokens (invitación, reset, feedback): almacenados hasheados
 * (SHA-256 es suficiente para tokens de alta entropía)". P-02 deja explícitamente el
 * algoritmo de hash fuera de la decisión de producto ("son detalles de implementación"), así
 * que SHA-256 es la elección de implementación aquí.
 *
 * <p>Se usa SHA-256 y <strong>no</strong> BCrypt a propósito: estos tokens no son
 * credenciales de baja entropía que un atacante pueda testar sin límite, sino valores de 256
 * bits aleatorios. Con BCrypt el lookup por hash sería además imposible de indexar.
 */
@Component
public class TokenHasher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    /** 256 bits de entropía, en Base64 URL-safe sin padding (≈43 caracteres). */
    public String generateOpaqueToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    /** SHA-256 en hexadecimal (64 caracteres): es lo que se persiste, nunca el token. */
    public String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible en esta JVM", e);
        }
    }
}

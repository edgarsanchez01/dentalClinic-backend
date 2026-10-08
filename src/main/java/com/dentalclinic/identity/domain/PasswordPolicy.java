package com.dentalclinic.identity.domain;

import java.nio.charset.StandardCharsets;

import com.dentalclinic.identity.domain.exception.PasswordExceedsHashingLimitException;

/**
 * Restricciones <strong>técnicas</strong> sobre la contraseña, derivadas del mecanismo de
 * hashing normativo. <em>No</em> es una política de contraseña de producto.
 *
 * <h2>Qué NO hace esta clase, y por qué</h2>
 *
 * PRD.md RF-IA-13 solo exige "hash de contraseñas adaptativo con sal (BCrypt o Argon2)".
 * Ni PRD.md, ni Architecture.md, ni Design System.md fijan longitud mínima, longitud
 * máxima, reglas de composición, caducidad ni historical de contraseñas. Por tanto esta
 * clase <strong>no</strong> exige mayúscula, minúscula, dígito, símbolo, longitud mínima
 * ni caducidad: hacerlo sería inventar una decisión de producto. La única validación es
 * la que ya impone el requisito de campo obligatorio ({@code @NotBlank}) más el límite
 * técnico descrito abajo.
 *
 * <h2>El límite técnico, verificado sobre la implementación real</h2>
 *
 * Verificado ejecutando {@code BCryptPasswordEncoder} de Spring Security 6.5.11
 * (el que resuelve Spring Boot 3.5.16):
 *
 * <ul>
 *   <li>{@code encode()} <strong>lanza</strong> {@code IllegalArgumentException:
 *       "password cannot be more than 72 bytes"} cuando la contraseña supera 72
 *       <em>bytes UTF-8</em>. No trunca en silencio al codificar.</li>
 *   <li>El límite se mide en <strong>bytes UTF-8</strong>, no en caracteres: 36×
 *       {@code á} (72 bytes) se codifica; 37× {@code á} (74 bytes) lanza. Con emoji el
 *       mismo corte se alcanza con 18 (72 bytes) frente a 19 (76 bytes).</li>
 *   <li>{@code matches()} <strong>no</strong> aplica ese límite: con un hash de 72
 *       caracteres, {@code matches()} de una contraseña de 73 caracteres devuelve
 *       {@code true}. Sin la comprobación previa, una contraseña larga podría autenticarse
 *       porque solo se compara su prefijo de 72 bytes.</li>
 * </ul>
 *
 * <p>Por eso la longitud se valida <strong>antes</strong> de llamar a BCrypt: para que una
 * contraseña de más de 72 bytes sea un 400 con mensaje accionable en lugar de un
 * {@code IllegalArgumentException} sin tratar (500) o un {@code matches()} que acepta un
 * prefijo truncado.
 *
 * <p>No es un {@code @Size(max = 72)} sobre caracteres: eso permitiría 72 caracteres
 * multibyte que ocupan más de 72 bytes y rompería el límite real del algoritmo.
 */
public final class PasswordPolicy {

    /**
     * Límite efectivo de BCrypt: 72 <em>bytes</em> de la contraseña en UTF-8.
     * Comportamiento medido, no supuesto.
     */
    public static final int MAX_SAFE_UTF8_BYTES = 72;

    private PasswordPolicy() {
    }

    /**
     * Devuelve {@code true} si la contraseña cabe con holgura en el límite del algoritmo.
     *
     * @param password contraseña en claro tal como la escribió el usuario
     */
    public static boolean fitsHashingLimit(String password) {
        return utf8Length(password) <= MAX_SAFE_UTF8_BYTES;
    }

    /** Longitud real en bytes UTF-8: es lo que BCrypt trunca, no el número de caracteres. */
    public static int utf8Length(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Verifica el límite técnico antes de hashear.
     *
     * @throws PasswordExceedsHashingLimitException si la contraseña excede los 72 bytes UTF-8
     */
    public static void requireWithinHashingLimit(String password) {
        if (!fitsHashingLimit(password)) {
            throw new PasswordExceedsHashingLimitException(utf8Length(password));
        }
    }
}
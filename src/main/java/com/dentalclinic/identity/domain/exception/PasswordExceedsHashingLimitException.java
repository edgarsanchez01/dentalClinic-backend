package com.dentalclinic.identity.domain.exception;

/**
 * La contraseña supera el límite técnico del mecanismo de hashing normativo
 * (72 bytes UTF-8 de BCrypt). Es una restricción del algoritmo, no una política de
 * contraseña de producto: ver {@code PasswordPolicy}.
 */
public class PasswordExceedsHashingLimitException extends RuntimeException {

    private final int actualUtf8Bytes;

    public PasswordExceedsHashingLimitException(int actualUtf8Bytes) {
        super("La contrasena es demasiado larga. Usa como maximo "
                + com.dentalclinic.identity.domain.PasswordPolicy.MAX_SAFE_UTF8_BYTES
                + " bytes UTF-8 o menos (el límite es del algoritmo, no de caracteres).");
        this.actualUtf8Bytes = actualUtf8Bytes;
    }

    public int getActualUtf8Bytes() {
        return actualUtf8Bytes;
    }
}

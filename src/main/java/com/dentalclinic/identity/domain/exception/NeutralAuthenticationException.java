package com.dentalclinic.identity.domain.exception;

/**
 * Fallo de autenticación cuya respuesta al cliente debe ser <strong>neutra</strong>: el
 * mismo status, el mismo mensaje y el mismo borrado de cookie para un refresh reutilizado,
 * expirado, revocado o simplemente desconocido.
 *
 * <p>Architecture.md §7.1: "Reutilizar un token ya rotado revoca la familia completa y
 * responde de forma neutra." §7.7: "La respuesta ante un refresh reutilizado, expirado o
 * revocado es neutra e idéntica."
 */
public class NeutralAuthenticationException extends RuntimeException {

    public NeutralAuthenticationException() {
        super("No se pudo renovar la sesión.");
    }
}

package com.dentalclinic.identity.domain.exception;

/**
 * El recurso no existe o no pertenece al usuario autenticado.
 *
 * <p>Architecture.md §8.2 lo fija de forma explícita: "NotFound | 404 | No existe (o no es tuyo →
 * 404)". Es una decisión de seguridad, no un descuido: devolver 404 cuando el recurso es de otro
 * usuario evita revelar que existe. Por eso un {@code SELF_READ} denegado se traduce a 404 y no
 * a 403, mientras que un 403 queda reservado a un fallo de <em>rol</em> (§7.4).
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException() {
        super("No encontramos el recurso solicitado.");
    }

    public NotFoundException(String message) {
        super(message);
    }
}
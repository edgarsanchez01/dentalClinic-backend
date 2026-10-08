package com.dentalclinic.identity.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * Canonicalización de la identidad de correo.
 *
 * <p><strong>Regla técnica, única y consistente:</strong> {@code trim + lowercase}.
 *
 * <p>Architecture.md §12.7 dice solo {@code "User": email UNIQUE}, sin definir comparación por
 * mayúsculas. Se aplica esta canonicalización antes de persistir y antes de cualquier búsqueda o
 * autenticación para que {@code Ana@clinica.com} y {@code ana@clinica.com} no puedan representar
 * dos identidades distintas, y para que el acceso no dependa del casing con el que se escribió el
 * email la primera vez.
 *
 * <p><strong>Lo que esta regla NO hace</strong>, deliberadamente: no elimina puntos, no elimina
 * alias {@code +}, no transforma dominios y no aplica ninguna regla específica de proveedor
 * (Gmail y otros). Eso pertenece a una decisión de producto si algún día se aprueba, y aquí sería
 * inventarlo.
 *
 * <p>El índice UNIQUE de {@code users.email} opera por tanto sobre el valor canónico ya
 * persistido: la unicidad la garantiza el constraint, no solo la aplicación (defense in depth,
 * §8.3).
 *
 * <p>El lowercase usa {@link Locale#ROOT} para que el resultado no dependa del locale del sistema
 * (regla de trí Turkish: {@code "I".toLowerCase(tr)} no es {@code "i"}).
 */
public final class EmailCanonicalizer {

    private EmailCanonicalizer() {
    }

    /**
     * @return el email canónico: sin espacios exteriores y en minúsculas
     * @throws NullPointerException si el email es {@code null}
     */
    public static String canonicalize(String email) {
        Objects.requireNonNull(email, "email es obligatorio");
        return email.strip().toLowerCase(Locale.ROOT);
    }
}

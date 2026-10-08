package com.dentalclinic.identity.infrastructure.security;

import java.util.Set;

/**
 * Nivel de acceso de un rol sobre un grupo de endpoints.
 *
 * <p>Es la traducción directa de la matriz de Architecture.md §7.4, que es <strong>normativa</strong>
 * ("El backend la implementa; el frontend solo la refleja", PRD.md §5.3).
 */
public enum AccessLevel {

    /** Sin acceso. La respuesta es <strong>403</strong>, nunca un 200 con campos ocultos (§7.4). */
    NONE,

    /** Lectura. Solo métodos seguros ({@code GET}, {@code HEAD}). */
    READ,

    /** Lectura y escritura. */
    READ_WRITE,

    /**
     * Lectura limitada a los recursos propios. La pertenencia se verifica en cada endpoint que
     * toque datos de un paciente, no solo en el listado (§7.5).
     */
    SELF_READ,

    /** Lectura y escritura limitadas a los recursos propios. */
    SELF_READ_WRITE;

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    public boolean allowsWrite(String httpMethod) {
        return this == READ_WRITE || this == SELF_READ_WRITE;
    }

    public boolean allowsRead(String httpMethod) {
        if (this == NONE) {
            return false;
        }
        return SAFE_METHODS.contains(httpMethod) || allowsWrite(httpMethod);
    }

    /** @return {@code true} si el acceso está limitado a recursos propios y hay que verificarlo */
    public boolean requiresOwnershipCheck() {
        return this == SELF_READ || this == SELF_READ_WRITE;
    }

    }
package com.dentalclinic.identity.infrastructure.security;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import com.dentalclinic.identity.domain.Role;

/**
 * Resolución de la matriz de autorización de Architecture.md §7.4.
 *
 * <p>Es el <strong>punto único de verdad</strong> sobre el que se construye la cadena de filtros
 * de Spring Security y sobre el que se construyen los tests exhaustivos de la matriz
 * (requisito de salida de Fase 2). Los {@code @PreAuthorize} de los controllers usan
 * {@link AuthorizationService}, que a su vez solo sabe de roles: las dos capas nunca pueden
 * contradecirse porque una consulta al patrón y una comparación de rol leen el mismo grupo.
 *
 * <p>Criterio de respuesta (§7.4): "Fallo de autorización debe devolver 403 con un código claro,
 * nunca un 200 con campos ocultos."
 */
@Component
public class EndpointAccessPolicy {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /**
     * @return el nivel de acceso del rol sobre la ruta, o vacío si la ruta no pertenece a
     *     ningún grupo de la matriz y por tanto queda fuera de su alcance
     */
    public Optional<AccessLevel> levelFor(String path, Role role) {
        for (EndpointGroup group : EndpointGroup.values()) {
            if (MATCHER.match(group.pattern(), path)) {
                return Optional.of(group.levelFor(role));
            }
        }
        return Optional.empty();
    }

    /** Decisión completa para una petición concreta, método incluido. */
    public Optional<EndpointDecision> decide(String path, String method, Role role) {
        return levelFor(path, role)
                .map(level -> EndpointDecision.of(level, level.allowsRead(method)));
    }

    /**
     * @param permitted si el rol tiene acceso al menos a leer esta ruta
     * @param ownershipCheckRequired si el acceso está acotado a recursos propios y el endpoint
     *     debe verificar la pertenencia en ejecución (§7.5)
     */
    public record EndpointDecision(AccessLevel level, boolean permitted, boolean ownershipCheckRequired) {

        static EndpointDecision of(AccessLevel level, boolean permitted) {
            return new EndpointDecision(level, permitted, level.requiresOwnershipCheck());
        }
    }
}
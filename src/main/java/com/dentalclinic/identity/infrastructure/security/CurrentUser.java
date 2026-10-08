package com.dentalclinic.identity.infrastructure.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Acceso al principal de la petición en curso.
 *
 * <p>Existe para que la capa de aplicación resuelva "quién soy" sin depender de Spring Web
 * (Architecture.md §6.2: al dominio no le corresponde conocer el transporte HTTP).
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** @return la identidad autenticada, o {@code null} si la petición no está autenticada */
    public static AuthenticatedUser get() {
        return principalOf(SecurityContextHolder.getContext().getAuthentication());
    }

    public static AuthenticatedUser require() {
        AuthenticatedUser user = get();
        if (user == null) {
            throw new IllegalStateException("No hay identidad autenticada en el contexto");
        }
        return user;
    }

    private static AuthenticatedUser principalOf(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        if (authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user;
        }
        return null;
    }
}

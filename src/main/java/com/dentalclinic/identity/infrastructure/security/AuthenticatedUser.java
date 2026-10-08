package com.dentalclinic.identity.infrastructure.security;

import com.dentalclinic.identity.domain.Role;

/**
 * Identidad autenticada de la petición en curso.
 *
 * <p>Es el principio de autenticación que transporta Spring Security. Sus campos son el mínimo
 * que la capa de autorización necesita; el contenido autoritativo del perfil vive en
 * {@code GET /api/auth/me} (§14.3, RF-IA-15).
 *
 * <p>No contiene ni email ni hash de contraseña: la autorización decide por {@code userId} y
 * {@code role}, que es exactamente lo que exige §7.2 ("el backend resuelve el rol desde
 * user.getRole() directamente"). El email no tiene por qué viajar en cada principal.
 */
public record AuthenticatedUser(Long userId, Role role) {
}

package com.dentalclinic.identity.infrastructure.security;

import org.springframework.stereotype.Service;

import com.dentalclinic.identity.domain.Role;

/**
 * Predicados de autorización por rol para {@code @PreAuthorize}.
 *
 * <p>Es la representación en código de §7.2 ("La lógica de autorización debe estar en el backend
 * y basarse en el rol del usuario tal como está definido en el modelo de datos"), con los mismos
 * predicados que usa el filtro de URL pero sobre el principal ya resuelto.
 *
 * <p>No hay predicados para "dueño de un recurso concreto" aquí a propósito: la pertenencia se
 * verifica en el servicio de aplicación que carga el recurso (§7.5).
 */
@Service("authorization")
public class AuthorizationService {

    public boolean isOwner(AuthenticatedUser user) {
        return user != null && user.role() == Role.OWNER;
    }

    /** Personal con acceso al historial clínico del paciente (§7.4). */
    public boolean isClinicalStaff(AuthenticatedUser user) {
        return user != null && (user.role() == Role.OWNER || user.role() == Role.DENTIST);
    }

    /** Cualquier rol interno; excluye al paciente. */
    public boolean isStaff(AuthenticatedUser user) {
        return user != null && user.role() != Role.PATIENT;
    }

    public boolean isPatient(AuthenticatedUser user) {
        return user != null && user.role() == Role.PATIENT;
    }
}
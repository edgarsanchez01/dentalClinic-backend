package com.dentalclinic.identity.api.dto;

import java.time.Instant;

import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.StaffStatus;

/**
 * Cuenta de personal en el listado de {@code GET /api/users}. Nunca expone el hash de contraseña.
 *
 * @param dentist perfil profesional si el rol es DENTIST (o el OWNER, que también es dentista);
 *     {@code null} para RECEPTIONIST
 * @param status estado administrativo derivado (Fase 8A): {@code ACTIVE}, {@code PENDING}
 *     (invitación sin usar) o {@code INACTIVE} (desactivada). {@code active} se conserva por
 *     compatibilidad con el contrato de Fase 3 y es {@code true} solo cuando {@code status} es
 *     {@code ACTIVE}
 */
public record StaffUserDto(
        Long id,
        String email,
        Role role,
        boolean active,
        Instant createdAt,
        DentistDto dentist,
        StaffStatus status) {

    public record DentistDto(
            String firstName,
            String lastName,
            String phone,
            String specialty,
            String licenseNumber,
            String publicInfo,
            String photoUrl) {
    }
}

package com.dentalclinic.identity.api.dto;

import java.time.Instant;

import com.dentalclinic.identity.domain.Role;

/**
 * Cuenta de personal en el listado de {@code GET /api/users}. Nunca expone el hash de contraseña.
 *
 * @param dentist perfil profesional si el rol es DENTIST (o el OWNER, que también es dentista);
 *     {@code null} para RECEPTIONIST
 */
public record StaffUserDto(
        Long id,
        String email,
        Role role,
        boolean active,
        Instant createdAt,
        DentistDto dentist) {

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
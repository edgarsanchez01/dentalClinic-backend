package com.dentalclinic.identity.api.dto;

import java.time.Instant;

import com.dentalclinic.identity.domain.Role;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Perfil autoritativo de la sesion. Nunca expone el hash de contrasena.
 *
 * <p>{@code patientId} solo aparece cuando el usuario autenticado tiene un {@code Patient}
 * realmente asociado (P-08, §14.3). {@code dentistId} solo aparece cuando el usuario tiene un
 * {@code Dentist} realmente asociado (A-11). Los campos se omiten: no se inventan ni se infieren
 * por email, nombre ni telefono.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserDto(
        Long id,
        String email,
        Role role,
        boolean active,
        Instant createdAt,
        Long patientId,
        Long dentistId) {
}
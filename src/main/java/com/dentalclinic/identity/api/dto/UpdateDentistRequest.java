package com.dentalclinic.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Edición administrativa del perfil profesional de una cuenta ({@code PUT
 * /api/users/{id}/dentist}, Fase 8A / RF-PR-05).
 *
 * <p>No contiene email, rol ni contraseña: el email es inmutable en {@code User} (identidad
 * histórica estable), el rol nunca cambia por este flujo (sin escalamiento) y la contraseña se
 * gestiona exclusivamente por los flujos propios de identidad, nunca por formularios
 * administrativos. Semántica PUT: los opcionales a {@code null} limpian el dato.
 */
public record UpdateDentistRequest(

        @NotBlank(message = "El nombre es obligatorio.")
        @Size(max = 100, message = "El nombre no puede superar 100 caracteres.")
        String firstName,

        @NotBlank(message = "Los apellidos son obligatorios.")
        @Size(max = 100, message = "Los apellidos no pueden superar 100 caracteres.")
        String lastName,

        @NotBlank(message = "El teléfono es obligatorio.")
        @Size(max = 50, message = "El teléfono no puede superar 50 caracteres.")
        String phone,

        @Size(max = 200, message = "La especialidad no puede superar 200 caracteres.")
        String specialty,

        @Size(max = 100, message = "El número de licencia no puede superar 100 caracteres.")
        String licenseNumber,

        String publicInfo,

        @Size(max = 2048, message = "La URL de la foto no puede superar 2048 caracteres.")
        String photoUrl) {
}

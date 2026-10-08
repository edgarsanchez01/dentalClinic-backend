package com.dentalclinic.identity.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Restablecimiento de contraseña con token de un solo uso.
 *
 * <p>No lleva identificador de usuario: el token es la única autorización.
 */
public record ResetPasswordRequest(

        @NotBlank(message = "El enlace no es válido o ha expirado.")
        String token,

        @NotBlank(message = "La contraseña es obligatoria.")
        String newPassword) {
}

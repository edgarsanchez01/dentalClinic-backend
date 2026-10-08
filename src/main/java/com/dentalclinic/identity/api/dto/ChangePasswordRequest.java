package com.dentalclinic.identity.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Cambio de contraseña autenticado (RF-IA-11).
 *
 * <p>{@code currentPassword} es obligatoria: sin ella, un access token robado bastaría para
 * apropiarse de la cuenta. No se anota con {@code @NotBlank} porque el servicio debe distinguir
 * "no la envió" (400 con mensaje propio) de "la envió y no coincide" (401).
 */
public record ChangePasswordRequest(String currentPassword,

        @NotBlank(message = "La contraseña es obligatoria.")
        String newPassword) {
}

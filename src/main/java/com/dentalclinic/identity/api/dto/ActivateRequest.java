package com.dentalclinic.identity.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Consumo de una invitación.
 *
 * <p>El <strong>rol no forma parte del cuerpo</strong>: sale siempre de la invitación. Aceptarlo
 * aquí reabriría la escalada de privilegios que RF-IA-06 cierra para el registro público y que
 * P-07 mantiene cerrada para OWNER.
 */
public record ActivateRequest(

        @NotBlank(message = "El enlace de activación no es válido.")
        String token,

        @NotBlank(message = "La contraseña es obligatoria.")
        String password) {
}

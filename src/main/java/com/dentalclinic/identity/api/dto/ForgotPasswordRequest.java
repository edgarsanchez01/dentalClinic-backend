package com.dentalclinic.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Solicitud de recuperación de contraseña.
 *
 * <p>No lleva token ni contraseña: solo pide el email al que se enviará el enlace cuando exista un
 * canal de entrega (Communications, Fase 8).
 */
public record ForgotPasswordRequest(

        @NotBlank(message = "El correo electrónico es obligatorio.")
        @Email(message = "Introduce un correo electrónico válido.")
        String email) {
}

package com.dentalclinic.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Credenciales de acceso.
 *
 * <p>Los mensajes de validación van en español y son accionables (§8.1). El campo se llama
 * {@code email} y no {@code username}: el producto no tiene otro identificador de acceso.
 *
 * <p>{@code @Email} de Bean Validation comprueba la forma. <strong>No</strong> normaliza a
 * minúsculas: la canonicalización {@code trim + lowercase} es una regla de identidad, no de
 * validación de formato, y se aplica en el dominio.
 */
public record LoginRequest(

        @NotBlank(message = "El correo electrónico es obligatorio.")
        @Email(message = "Introduce un correo electrónico válido.")
        String email,

        @NotBlank(message = "La contraseña es obligatoria.")
        String password) {

    @SuppressWarnings("unused")
    public LoginRequest {
        // Recorte defensivo para que la validación de forma no rechace un email con espacios
        // accidentales antes de que el dominio aplique trim + lowercase. La minúscula sigue
        // siendo competencia del dominio (EmailCanonicalizer), no de esta validación.
        email = email == null ? null : email.trim();
    }
}

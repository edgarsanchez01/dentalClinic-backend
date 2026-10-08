package com.dentalclinic.identity.api.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registro publico de un paciente (RF-IA-05/06).
 *
 * <p>El rol <strong>nunca</strong> forma parte del cuerpo: el backend fija siempre
 * {@code PATIENT}. Los campos obligatorios son email, password, nombre y apellido; telefono y
 * fecha de nacimiento son opcionales.
 */
public record RegisterRequest(
        @NotBlank(message = "El email es obligatorio.")
        @Email(message = "El email no tiene un formato valido.")
        @Size(max = 320, message = "El email no puede superar los 320 caracteres.")
        String email,

        @NotBlank(message = "La contrasena es obligatoria.")
        String password,

        @NotBlank(message = "El nombre es obligatorio.")
        @Size(max = 100, message = "El nombre no puede superar los 100 caracteres.")
        String firstName,

        @NotBlank(message = "El apellido es obligatorio.")
        @Size(max = 100, message = "El apellido no puede superar los 100 caracteres.")
        String lastName,

        @Size(max = 50, message = "El telefono no puede superar los 50 caracteres.")
        String phone,

        LocalDate birthDate) {
}
package com.dentalclinic.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Alta de un dentista por el OWNER ({@code POST /api/users/dentists}). El rol lo fija la ruta. */
public record CreateDentistRequest(

        @NotBlank(message = "El email es obligatorio.")
        @Email(message = "El email no tiene un formato válido.")
        @Size(max = 320, message = "El email no puede superar 320 caracteres.")
        String email,

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
package com.dentalclinic.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Alta de un recepcionista por el OWNER ({@code POST /api/users/receptionists}). */
public record CreateReceptionistRequest(

        @NotBlank(message = "El email es obligatorio.")
        @Email(message = "El email no tiene un formato válido.")
        @Size(max = 320, message = "El email no puede superar 320 caracteres.")
        String email) {
}
package com.dentalclinic.clinic.api.dto;

import java.util.Map;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Actualización de la configuración de la clínica (RF-CL-03, solo OWNER).
 *
 * <p>{@code name} y {@code timezone} son obligatorios: son los únicos campos que el sistema
 * necesita para operar. El resto es opcional y {@code null} lo limpia (semántica PUT de
 * reemplazo).
 */
public record UpdateClinicRequest(

        @NotBlank(message = "El nombre de la clínica es obligatorio.")
        @Size(max = 200, message = "El nombre no puede superar 200 caracteres.")
        String name,

        @NotBlank(message = "La zona horaria es obligatoria.")
        @Size(max = 64, message = "La zona horaria no puede superar 64 caracteres.")
        String timezone,

        String description,

        @Size(max = 2048, message = "La URL del logo no puede superar 2048 caracteres.")
        String logoUrl,

        String address,

        @Size(max = 50, message = "El teléfono no puede superar 50 caracteres.")
        String phone,

        @Email(message = "El email no tiene un formato válido.")
        @Size(max = 320, message = "El email no puede superar 320 caracteres.")
        String email,

        String publicInfo,

        @Pattern(regexp = "^#[0-9A-Fa-f]{6}$",
                message = "El color de marca debe tener el formato #RRGGBB.")
        String brandColor,

        Map<String, String> socialNetworks) {
}
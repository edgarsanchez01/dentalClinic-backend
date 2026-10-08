package com.dentalclinic.identity.api.dto;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo del onboarding (RF-IA-01, PRD.md §6.3-6.5).
 *
 * <p>Obligatorios: en el OWNER, {@code email} y {@code password}; en el dentista,
 * {@code firstName}, {@code lastName} y {@code phone}; en la clínica, {@code name} y
 * {@code timezone}. El resto es opcional: la especialidad, la cédula, el logo, las redes o el color
 * de marca no se exigen para configurar la instalación.
 */
public record OnboardingRequest(
        @NotNull(message = "El propietario es obligatorio.")
        @Valid Owner owner,

        @NotNull(message = "El dentista es obligatorio.")
        @Valid Dentist dentist,

        @NotNull(message = "La clínica es obligatoria.")
        @Valid Clinic clinic) {

    public record Owner(
            @NotBlank(message = "El email del propietario es obligatorio.")
            @Email(message = "El email del propietario no tiene un formato válido.")
            @Size(max = 320, message = "El email no puede superar 320 caracteres.")
            String email,

            @NotBlank(message = "La contraseña es obligatoria.")
            String password) {
    }

    public record Dentist(
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

    public record Clinic(
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

            @Email(message = "El email de la clínica no tiene un formato válido.")
            @Size(max = 320, message = "El email no puede superar 320 caracteres.")
            String email,

            String publicInfo,

            @Pattern(regexp = "^#[0-9A-Fa-f]{6}$",
                    message = "El color de marca debe tener el formato #RRGGBB.")
            String brandColor,

            Map<String, String> socialNetworks) {
    }
}
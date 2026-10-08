package com.dentalclinic.patient.api.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Alta administrativa de un paciente (RF-PA-02). No crea cuenta de usuario. */
public record CreatePatientRequest(
        @NotBlank(message = "El nombre es obligatorio.")
        @Size(max = 100, message = "El nombre no puede superar los 100 caracteres.")
        String firstName,

        @NotBlank(message = "El apellido es obligatorio.")
        @Size(max = 100, message = "El apellido no puede superar los 100 caracteres.")
        String lastName,

        @Email(message = "El email no tiene un formato valido.")
        @Size(max = 320, message = "El email no puede superar los 320 caracteres.")
        String email,

        @Size(max = 50, message = "El telefono no puede superar los 50 caracteres.")
        String phone,

        LocalDate birthDate,

        String administrativeNotes) {
}
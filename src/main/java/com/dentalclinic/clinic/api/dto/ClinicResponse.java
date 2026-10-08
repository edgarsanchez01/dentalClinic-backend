package com.dentalclinic.clinic.api.dto;

import java.time.Instant;
import java.util.Map;

import com.dentalclinic.clinic.domain.Clinic;

/** Representación pública de la clínica. No incluye secretos (no hay ninguno en la entidad). */
public record ClinicResponse(
        Long id,
        String name,
        String description,
        String logoUrl,
        String address,
        String phone,
        String email,
        String publicInfo,
        String brandColor,
        Map<String, String> socialNetworks,
        String timezone,
        Instant createdAt,
        Instant updatedAt) {

    public static ClinicResponse from(Clinic clinic) {
        return new ClinicResponse(
                clinic.getId(),
                clinic.getName(),
                clinic.getDescription(),
                clinic.getLogoUrl(),
                clinic.getAddress(),
                clinic.getPhone(),
                clinic.getEmail(),
                clinic.getPublicInfo(),
                clinic.getBrandColor(),
                clinic.getSocialNetworks(),
                clinic.getTimezone(),
                clinic.getCreatedAt(),
                clinic.getUpdatedAt());
    }
}
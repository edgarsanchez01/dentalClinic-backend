package com.dentalclinic.identity.api.dto;

/** Resultado del onboarding (201): la instalación queda COMPLETED con su clínica y su OWNER. */
public record OnboardingResponse(String status, Long ownerUserId, Long clinicId) {
}
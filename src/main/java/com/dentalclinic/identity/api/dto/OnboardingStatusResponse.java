package com.dentalclinic.identity.api.dto;

import com.dentalclinic.identity.domain.InstallationStatus;

/** Estado de la instalación según el marker técnico (PRD.md §6.6). */
public record OnboardingStatusResponse(InstallationStatus status, boolean clinicConfigured) {
}
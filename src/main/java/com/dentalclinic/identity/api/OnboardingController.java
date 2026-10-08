package com.dentalclinic.identity.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dentalclinic.clinic.application.ClinicService.ClinicData;
import com.dentalclinic.clinic.application.DentistService.DentistData;
import com.dentalclinic.identity.api.dto.OnboardingRequest;
import com.dentalclinic.identity.api.dto.OnboardingResponse;
import com.dentalclinic.identity.api.dto.OnboardingStatusResponse;
import com.dentalclinic.identity.application.InstallationStatusService;
import com.dentalclinic.identity.application.OnboardingService;
import com.dentalclinic.identity.application.OnboardingService.OnboardingCommand;
import com.dentalclinic.identity.application.OnboardingService.OwnerData;

import jakarta.validation.Valid;

/**
 * Onboarding de la instalación.
 *
 * <p>Ambas rutas son públicas en la allowlist de {@code SecurityConfig} ({@code /api/onboarding} y
 * {@code /api/onboarding/status}), porque la primera configuración ocurre antes de que exista
 * ningún usuario autenticado. La allowlist es una excepción explícita y condicionada, no un
 * debilitamiento general: {@code EndpointAccessFilter} también las excluye de la matriz para no
 * denegarlas con 403 antes de llegar al controlador.
 */
@RestController
@RequestMapping("/api/onboarding")
public class OnboardingController {

    private final OnboardingService onboardingService;
    private final InstallationStatusService installationStatusService;

    public OnboardingController(OnboardingService onboardingService,
                                InstallationStatusService installationStatusService) {
        this.onboardingService = onboardingService;
        this.installationStatusService = installationStatusService;
    }

    @GetMapping("/status")
    public OnboardingStatusResponse status() {
        InstallationStatusService.InstallationStatusView view = installationStatusService.current();
        return new OnboardingStatusResponse(view.status(), view.clinicConfigured());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OnboardingResponse onboard(@Valid @RequestBody OnboardingRequest request) {
        OnboardingService.OnboardingResult result = onboardingService.onboard(new OnboardingCommand(
                new OwnerData(request.owner().email(), request.owner().password()),
                new DentistData(request.dentist().firstName(), request.dentist().lastName(),
                        request.dentist().phone(), request.dentist().specialty(),
                        request.dentist().licenseNumber(), request.dentist().publicInfo(),
                        request.dentist().photoUrl()),
                new ClinicData(request.clinic().name(), request.clinic().timezone(),
                        request.clinic().description(), request.clinic().logoUrl(),
                        request.clinic().address(), request.clinic().phone(), request.clinic().email(),
                        request.clinic().publicInfo(), request.clinic().brandColor(),
                        request.clinic().socialNetworks())));
        return new OnboardingResponse("COMPLETED", result.ownerUserId(), result.clinicId());
    }
}
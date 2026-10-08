package com.dentalclinic.identity.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.clinic.application.ClinicService;
import com.dentalclinic.identity.domain.InstallationStatus;
import com.dentalclinic.identity.infrastructure.repository.InstallationStateRepository;

/**
 * Estado de la instalación para el guard del frontend (RF-IA-01, PRD.md §6.6).
 *
 * <p>El estado sale del marker técnico, no de heurísticas sobre {@code User} o {@code Clinic}
 * (PRD.md §6.2): la ausencia de fila es {@link InstallationStatus#AWAITING_FIRST_USER}. El flag
 * {@code clinicConfigured} se incluye por comodidad del cliente y se calcula a partir de la
 * existencia real de la clínica.
 */
@Service
public class InstallationStatusService {

    private final InstallationStateRepository installationStateRepository;
    private final ClinicService clinicService;

    public InstallationStatusService(InstallationStateRepository installationStateRepository,
                                     ClinicService clinicService) {
        this.installationStateRepository = installationStateRepository;
        this.clinicService = clinicService;
    }

    @Transactional(readOnly = true)
    public InstallationStatusView current() {
        InstallationStatus status = installationStateRepository.findAll().stream()
                .findFirst()
                .map(state -> state.getStatus())
                .orElse(InstallationStatus.AWAITING_FIRST_USER);
        return new InstallationStatusView(status, clinicService.exists());
    }

    public record InstallationStatusView(InstallationStatus status, boolean clinicConfigured) {
    }
}
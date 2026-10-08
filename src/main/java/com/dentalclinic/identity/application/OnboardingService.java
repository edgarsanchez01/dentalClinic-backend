package com.dentalclinic.identity.application;

import java.time.Clock;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.clinic.application.ClinicService;
import com.dentalclinic.clinic.application.ClinicService.ClinicData;
import com.dentalclinic.clinic.application.DentistService;
import com.dentalclinic.clinic.application.DentistService.DentistData;
import com.dentalclinic.identity.domain.EmailCanonicalizer;
import com.dentalclinic.identity.domain.InstallationState;
import com.dentalclinic.identity.domain.PasswordPolicy;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.domain.exception.EmailAlreadyRegisteredException;
import com.dentalclinic.identity.domain.exception.InstallationAlreadyConfiguredException;
import com.dentalclinic.identity.domain.exception.InstallationConcurrencyConflictException;
import com.dentalclinic.identity.infrastructure.lock.InstallationLock;
import com.dentalclinic.identity.infrastructure.repository.InstallationStateRepository;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;

/**
 * Onboarding inicial (RF-IA-01, Architecture.md §15.5, PRD.md §6.3-6.5).
 *
 * <p>Crea, en una <strong>única transacción</strong>, exactamente tres cosas: la {@code Clinic},
 * el {@code User(OWNER)} activo y su {@code Dentist}. Nada más. En particular, <strong>no</strong>
 * crea servicios, catálogo de tratamientos, horarios ni descansos: eso es Fase 5 (scheduling) y
 * Fase 7 (tratamientos), y adelantarlo sería inventar alcance. La invitación de personal es una
 * operación aparte del OWNER ya autenticado (Fase 3, {@code /api/users}).
 *
 * <h2>Los dos códigos, deliberadamente distintos</h2>
 *
 * <ul>
 *   <li><strong>403</strong> ({@link InstallationAlreadyConfiguredException}): la comprobación
 *       previa al lock ve la instalación ya configurada. Es una acción prohibida sobre el estado de
 *       la instalación, no un conflicto de datos.</li>
 *   <li><strong>409</strong> ({@link InstallationConcurrencyConflictException}): dos peticiones
 *       pasaron la comprobación previa a la vez y una perdió la carrera dentro del lock. Es un
 *       conflicto concurrente, no una acción prohibida. No se usa 401 en ninguno de los dos casos.
 *       </li>
 * </ul>
 *
 * <p>La invariante persistente es la fila única de {@code installation_state}; el
 * {@code pg_advisory_xact_lock} es la exclusión mutua que evita que dos peticiones intenten
 * insertarla a la vez.
 */
@Service
public class OnboardingService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ClinicService clinicService;
    private final DentistService dentistService;
    private final InstallationStateRepository installationStateRepository;
    private final InstallationLock installationLock;
    private final Clock clock;

    public OnboardingService(UserRepository userRepository,
                             PasswordEncoder passwordEncoder,
                             ClinicService clinicService,
                             DentistService dentistService,
                             InstallationStateRepository installationStateRepository,
                             InstallationLock installationLock,
                             Clock clock) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.clinicService = clinicService;
        this.dentistService = dentistService;
        this.installationStateRepository = installationStateRepository;
        this.installationLock = installationLock;
        this.clock = clock;
    }

    @Transactional
    public OnboardingResult onboard(OnboardingCommand command) {
        // 1. Precondición sin lock: si ya está configurada, es una acción prohibida (403).
        if (installationStateRepository.count() > 0) {
            throw new InstallationAlreadyConfiguredException();
        }

        // 2. Exclusión mutua: serializa los intentos simultáneos.
        installationLock.acquire();

        // 3. Re-comprobación bajo lock: si el ganador ya configuró, esto es una carrera (409).
        if (installationStateRepository.count() > 0) {
            throw new InstallationConcurrencyConflictException();
        }

        PasswordPolicy.requireWithinHashingLimit(command.owner().password());
        String ownerEmail = EmailCanonicalizer.canonicalize(command.owner().email());
        if (userRepository.existsByEmail(ownerEmail)) {
            throw new EmailAlreadyRegisteredException();
        }

        User owner = userRepository.saveAndFlush(User.create(ownerEmail,
                passwordEncoder.encode(command.owner().password()), Role.OWNER));

        var clinic = clinicService.create(command.clinic());
        dentistService.createForUser(owner.getId(), command.dentist());

        installationStateRepository.save(InstallationState.completed(clock.instant()));

        return new OnboardingResult(owner.getId(), owner.getEmail(), clinic.getId());
    }

    /** Comando completo del onboarding. */
    public record OnboardingCommand(OwnerData owner, DentistData dentist, ClinicData clinic) {
    }

    public record OwnerData(String email, String password) {
    }

    public record OnboardingResult(Long ownerUserId, String ownerEmail, Long clinicId) {
    }
}
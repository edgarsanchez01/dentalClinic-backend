package com.dentalclinic.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.dentalclinic.clinic.application.ClinicService.ClinicData;
import com.dentalclinic.clinic.application.DentistService.DentistData;
import com.dentalclinic.clinic.infrastructure.repository.ClinicRepository;
import com.dentalclinic.clinic.infrastructure.repository.DentistRepository;
import com.dentalclinic.identity.IdentityIntegrationTestSupport;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.application.OnboardingService.OnboardingCommand;
import com.dentalclinic.identity.application.OnboardingService.OwnerData;
import com.dentalclinic.identity.domain.InstallationStatus;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.domain.exception.EmailAlreadyRegisteredException;
import com.dentalclinic.identity.domain.exception.InstallationAlreadyConfiguredException;
import com.dentalclinic.identity.domain.exception.InstallationConcurrencyConflictException;
import com.dentalclinic.identity.infrastructure.repository.InstallationStateRepository;
import com.dentalclinic.identity.infrastructure.repository.InvitationRepository;
import com.dentalclinic.identity.infrastructure.repository.PasswordResetTokenRepository;
import com.dentalclinic.identity.infrastructure.repository.RefreshSessionRepository;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;

/**
 * Comportamiento del onboarding como unidad transaccional (Fase 3): crea exactamente Clinic +
 * User(OWNER) + Dentist + marker, es idempotente hacia fuera con 403 y no deja residuos si falla.
 *
 * <p>La carrera concurrente se prueba con hilos reales: exactamente uno gana; el resto recibe 409
 * (perdió dentro del lock) o 403 (ya estaba configurada al llegar). Nunca 401.
 */
class OnboardingServiceIntegrationTest extends IdentityIntegrationTestSupport {

    @Autowired
    private OnboardingService onboardingService;
    @Autowired
    private InstallationStatusService installationStatusService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private InvitationRepository invitationRepository;
    @Autowired
    private PasswordResetTokenRepository resetTokenRepository;
    @Autowired
    private RefreshSessionRepository refreshSessionRepository;
    @Autowired
    private DentistRepository dentistRepository;
    @Autowired
    private ClinicRepository clinicRepository;
    @Autowired
    private InstallationStateRepository installationStateRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanSlate() {
        TestTime.reset();
        refreshSessionRepository.deleteAll();
        resetTokenRepository.deleteAll();
        invitationRepository.deleteAll();
        dentistRepository.deleteAll();
        clinicRepository.deleteAll();
        installationStateRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void creaClinicaOwnerDentistYMarkerEnUnaTransaccion() {
        onboardingService.onboard(command("owner@clinica.test"));

        assertThat(clinicRepository.count()).isEqualTo(1);
        assertThat(dentistRepository.count()).isEqualTo(1);
        assertThat(installationStateRepository.count()).isEqualTo(1);

        User owner = userRepository.findByEmail("owner@clinica.test").orElseThrow();
        assertThat(owner.getRole()).isEqualTo(Role.OWNER);
        assertThat(owner.isActive()).isTrue();

        var dentist = dentistRepository.findByUserId(owner.getId()).orElseThrow();
        assertThat(dentist.getFirstName()).isEqualTo("Ana");

        var view = installationStatusService.current();
        assertThat(view.status()).isEqualTo(InstallationStatus.COMPLETED);
        assertThat(view.clinicConfigured()).isTrue();
    }

    @Test
    void unSegundoIntentoEsAccionProhibida() {
        onboardingService.onboard(command("owner@clinica.test"));

        assertThatThrownBy(() -> onboardingService.onboard(command("otro@clinica.test")))
                .isInstanceOf(InstallationAlreadyConfiguredException.class);
    }

    @Test
    void siElEmailYaExisteTodoSeRevierte() {
        userRepository.saveAndFlush(User.create("owner@clinica.test",
                passwordEncoder.encode("ClaveExistente-1"), Role.RECEPTIONIST));

        assertThatThrownBy(() -> onboardingService.onboard(command("owner@clinica.test")))
                .isInstanceOf(EmailAlreadyRegisteredException.class);

        assertThat(clinicRepository.count()).isZero();
        assertThat(dentistRepository.count()).isZero();
        assertThat(installationStateRepository.count()).isZero();
    }

    @Test
    void laCarreraConcurrenteDejaUnUnicoGanador() throws Exception {
        int contenders = 8;
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<Throwable>> results = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            int index = i;
            Callable<Throwable> task = () -> {
                ready.countDown();
                start.await();
                try {
                    onboardingService.onboard(command("owner" + index + "@clinica.test"));
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            };
            results.add(pool.submit(task));
        }

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int wins = 0;
        List<Throwable> failures = new ArrayList<>();
        for (Future<Throwable> future : results) {
            Throwable outcome = future.get(30, TimeUnit.SECONDS);
            if (outcome == null) {
                wins++;
            } else {
                failures.add(outcome);
            }
        }
        pool.shutdownNow();

        assertThat(wins).as("solo una petición puede completar el onboarding").isEqualTo(1);
        assertThat(failures).hasSize(contenders - 1);
        assertThat(failures).allSatisfy(failure ->
                assertThat(failure).isInstanceOfAny(InstallationConcurrencyConflictException.class,
                        InstallationAlreadyConfiguredException.class));
        assertThat(failures).noneMatch(failure -> failure instanceof EmailAlreadyRegisteredException);

        assertThat(clinicRepository.count()).isEqualTo(1);
        assertThat(installationStateRepository.count()).isEqualTo(1);
        assertThat(userRepository.count()).isEqualTo(1);
    }

    private OnboardingCommand command(String email) {
        return new OnboardingCommand(
                new OwnerData(email, "ClaveOnboarding-1"),
                new DentistData("Ana", "Perez", "+34 600 000 000", "Ortodoncia", null, null, null),
                new ClinicData("Clinica Dental", "Europe/Madrid", null, null, null, null, null, null,
                        "#1A73E8", null));
    }
}
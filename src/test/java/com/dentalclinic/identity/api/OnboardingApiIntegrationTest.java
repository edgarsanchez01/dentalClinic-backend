package com.dentalclinic.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.dentalclinic.clinic.infrastructure.repository.ClinicRepository;
import com.dentalclinic.clinic.infrastructure.repository.DentistRepository;
import com.dentalclinic.identity.IdentityIntegrationTestSupport;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.infrastructure.repository.InstallationStateRepository;
import com.dentalclinic.identity.infrastructure.repository.InvitationRepository;
import com.dentalclinic.identity.infrastructure.repository.PasswordResetTokenRepository;
import com.dentalclinic.identity.infrastructure.repository.RefreshSessionRepository;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;

/**
 * Contrato HTTP del onboarding (Fase 3): allowlist pública, estado público, 201 la primera vez y
 * <strong>403</strong> (no 401, no 409) la segunda.
 *
 * <p>El 409 de carrera concurrente se cubre en su propio test de servicio, porque exige paralelismo
 * real y no una segunda petición secuencial.
 */
@AutoConfigureMockMvc
class OnboardingApiIntegrationTest extends IdentityIntegrationTestSupport {

    @Autowired
    private MockMvc mockMvc;
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
    void elEstadoInicialEsAwaitingFirstUser() throws Exception {
        mockMvc.perform(get("/api/onboarding/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_FIRST_USER"))
                .andExpect(jsonPath("$.clinicConfigured").value(false));
    }

    @Test
    void elPrimerOnboardingDevuelve201YConfiguraLaInstalacion() throws Exception {
        mockMvc.perform(post("/api/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.clinicId").isNumber());

        assertThat(clinicRepository.count()).isEqualTo(1);
        assertThat(dentistRepository.count()).isEqualTo(1);
        assertThat(installationStateRepository.count()).isEqualTo(1);

        var owner = userRepository.findByEmail("owner@clinica.test").orElseThrow();
        assertThat(owner.getRole()).isEqualTo(Role.OWNER);
        assertThat(owner.isActive()).isTrue();

        mockMvc.perform(get("/api/onboarding/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.clinicConfigured").value(true));
    }

    @Test
    void unSegundoOnboardingEs403No401Ni409() throws Exception {
        mockMvc.perform(post("/api/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void datosInvalidosDevuelven400ConFieldErrors() throws Exception {
        mockMvc.perform(post("/api/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "owner": { "email": "no-es-un-email", "password": "" },
                                  "dentist": { "firstName": "", "lastName": "", "phone": "" },
                                  "clinic": { "name": "", "timezone": "" } }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isMap());
    }

    @Test
    void onboardingSinBloqueDentistEs400YNoEscribeNada() throws Exception {
        mockMvc.perform(post("/api/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "owner":  { "email": "owner@clinica.test",
                                              "password": "ClaveOnboarding-1" },
                                  "clinic": { "name": "Clinica Dental", "timezone": "Europe/Madrid" } }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors").isMap())
                .andExpect(jsonPath("$.fieldErrors.dentist").exists());
        assertNoWriteOccurred();
    }

    @Test
    void onboardingConDentistNullEs400YNoEscribeNada() throws Exception {
        mockMvc.perform(post("/api/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "owner":   { "email": "owner@clinica.test",
                                               "password": "ClaveOnboarding-1" },
                                  "dentist": null,
                                  "clinic":  { "name": "Clinica Dental",
                                               "timezone": "Europe/Madrid" } }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.dentist").exists());
        assertNoWriteOccurred();
    }

    @Test
    void onboardingSinOwnerNiClinicEs400YNoEscribeNada() throws Exception {
        mockMvc.perform(post("/api/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "dentist": { "firstName": "Ana", "lastName": "Perez",
                                               "phone": "+34 600 000 000" } }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.owner").exists())
                .andExpect(jsonPath("$.fieldErrors.clinic").exists());
        assertNoWriteOccurred();
    }

    @Test
    void lasRutasDeGestionRequierenAutenticacion() throws Exception {
        mockMvc.perform(get("/api/clinic")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
    }

    /** Un request rechazado en la frontera HTTP no debe persistir User, Clinic, Dentist ni marker. */
    private void assertNoWriteOccurred() {
        assertThat(userRepository.count()).isZero();
        assertThat(clinicRepository.count()).isZero();
        assertThat(dentistRepository.count()).isZero();
        assertThat(installationStateRepository.count()).isZero();
    }

    private String validBody() {
        return """
                {
                  "owner":   { "email": "owner@clinica.test", "password": "ClaveOnboarding-1" },
                  "dentist": { "firstName": "Ana", "lastName": "Perez", "phone": "+34 600 000 000" },
                  "clinic":  { "name": "Clinica Dental", "timezone": "Europe/Madrid",
                               "brandColor": "#1A73E8" }
                }
                """;
    }
}
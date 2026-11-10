package com.dentalclinic.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.dentalclinic.clinic.application.DentistService;
import com.dentalclinic.clinic.application.DentistService.DentistData;
import com.dentalclinic.identity.IdentityIntegrationTestSupport;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.application.StaffInvitationService;
import com.dentalclinic.identity.application.StaffInvitationService.NewDentist;
import com.dentalclinic.identity.application.StaffInvitationService.NewReceptionist;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.infrastructure.repository.InvitationRepository;
import com.dentalclinic.identity.infrastructure.repository.RefreshSessionRepository;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Fase 8A (RF-PR-05): {@code PUT /api/users/{id}/dentist} y {@code PATCH /api/users/{id}/active}
 * por HTTP real (MockMvc). Comprueba la matriz §7.4 (solo OWNER), la validación de entrada, el
 * código por excepción (§8.2) y el contrato del cuerpo —sin email, rol ni contraseña editables.
 */
@AutoConfigureMockMvc
class UserManagementApiIntegrationTest extends IdentityIntegrationTestSupport {

    private static final String PASSWORD = "ClaveGestion-2026";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private InvitationRepository invitationRepository;
    @Autowired
    private RefreshSessionRepository refreshSessionRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private StaffInvitationService staffInvitationService;
    @Autowired
    private DentistService dentistService;

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private Long ownerId;
    private String ownerToken;
    private Long dentistId;
    private String dentistToken;
    private Long receptionistId;
    private String receptionistToken;
    private Long patientId;
    private String patientToken;

    @BeforeEach
    void seedTeam() throws Exception {
        TestTime.reset();
        invitationRepository.deleteAll();
        refreshSessionRepository.deleteAll();
        userRepository.deleteAll();

        ownerId = seedUser("duena@clinica.test", Role.OWNER);
        dentistId = seedUser("dentista@clinica.test", Role.DENTIST);
        dentistService.createForUser(dentistId,
                new DentistData("Luis", "Gomez", "+34 611 111 111", "Endodoncia", "CED-1", null, null));
        receptionistId = seedUser("recepcion@clinica.test", Role.RECEPTIONIST);
        patientId = seedUser("paciente@clinica.test", Role.PATIENT);

        ownerToken = loginAccessToken("duena@clinica.test");
        dentistToken = loginAccessToken("dentista@clinica.test");
        receptionistToken = loginAccessToken("recepcion@clinica.test");
        patientToken = loginAccessToken("paciente@clinica.test");
    }

    // ------------------------------------------------------------------ GET /api/users

    @Test
    void listarEquipoDevuelveElEstadoDerivadoSinExponerHash() throws Exception {
        staffInvitationService.inviteDentist(new NewDentist("pendiente@clinica.test",
                "Ana", "Perez", "+34 611 000 000", null, null, null, null));
        var desactivable = seedUser("baja@clinica.test", Role.RECEPTIONIST);
        staffInvitationService.setActive(desactivable, false,
                new com.dentalclinic.identity.infrastructure.security.AuthenticatedUser(ownerId, Role.OWNER));

        MvcResult result = mockMvc.perform(get("/api/users").header("Authorization", bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].passwordHash").doesNotExist())
                .andReturn();

        JsonNode equipo = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        var porEmail = new java.util.HashMap<String, JsonNode>();
        equipo.forEach(miembro -> porEmail.put(miembro.get("email").asText(), miembro));

        assertThat(porEmail.get("dentista@clinica.test").get("status").asText()).isEqualTo("ACTIVE");
        assertThat(porEmail.get("dentista@clinica.test").get("active").asBoolean()).isTrue();
        assertThat(porEmail.get("recepcion@clinica.test").get("status").asText()).isEqualTo("ACTIVE");
        assertThat(porEmail.get("pendiente@clinica.test").get("status").asText()).isEqualTo("PENDING");
        assertThat(porEmail.get("pendiente@clinica.test").get("active").asBoolean())
                .as("una cuenta sin activar nunca está activa").isFalse();
        assertThat(porEmail.get("baja@clinica.test").get("status").asText()).isEqualTo("INACTIVE");
        assertThat(porEmail.get("baja@clinica.test").get("active").asBoolean()).isFalse();
        assertThat(porEmail.get("duena@clinica.test").get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void listarEquipoSinTokenEs401() throws Exception {
        mockMvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ PUT /{id}/dentist

    @Test
    void editarDentistComoOwnerActualizaElPerfilSinTocarIdentidad() throws Exception {
        mockMvc.perform(put("/api/users/{id}/dentist", dentistId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"firstName":"María","lastName":"López Salas","phone":"+34 622 222 222",
                 "specialty":"Ortodoncia","licenseNumber":"CED-2","publicInfo":"info nueva"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(dentistId))
                .andExpect(jsonPath("$.email").value("dentista@clinica.test"))
                .andExpect(jsonPath("$.role").value("DENTIST"))
                .andExpect(jsonPath("$.dentist.firstName").value("María"))
                .andExpect(jsonPath("$.dentist.lastName").value("López Salas"))
                .andExpect(jsonPath("$.dentist.specialty").value("Ortodoncia"))
                .andExpect(jsonPath("$.dentist.licenseNumber").value("CED-2"))
                .andExpect(jsonPath("$.dentist.publicInfo").value("info nueva"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        User user = userRepository.findById(dentistId).orElseThrow();
        assertThat(user.getEmail()).as("el email es inmutable").isEqualTo("dentista@clinica.test");
        assertThat(user.getRole()).isEqualTo(Role.DENTIST);
    }

    @Test
    void editarDentistCuerpoInvalidoDevuelve400ConFieldErrors() throws Exception {
        mockMvc.perform(put("/api/users/{id}/dentist", dentistId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"firstName":"   ","lastName":"","phone":"no-telefono"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.firstName").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors.lastName").isNotEmpty());
    }

    @Test
    void editarDentistSobreRecepcionEs403() throws Exception {
        mockMvc.perform(put("/api/users/{id}/dentist", receptionistId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"firstName":"Ana","lastName":"Recepción","phone":"+34 600 000 000"}
                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void editarDentistSobrePacienteEs403() throws Exception {
        mockMvc.perform(put("/api/users/{id}/dentist", patientId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"firstName":"Paci","lastName":"Ente","phone":"+34 600 000 000"}
                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void editarDentistCuentaInexistenteEs404() throws Exception {
        mockMvc.perform(put("/api/users/{id}/dentist", 999_999L)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"firstName":"X","lastName":"Y","phone":"+34 600 000 000"}
                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void editarDentistComoNoOwnerEs403() throws Exception {
        for (String token : new String[] {dentistToken, receptionistToken, patientToken}) {
            mockMvc.perform(put("/api/users/{id}/dentist", dentistId)
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                    {"firstName":"María","lastName":"López","phone":"+34 622 222 222"}
                    """))
                    .andExpect(status().isForbidden());
        }
    }

    // ------------------------------------------------------------------ PATCH /{id}/active

    @Test
    void desactivarCuentaComoOwnerDevuelveInactivaYRetiraElAcceso() throws Exception {
        mockMvc.perform(patch("/api/users/{id}/active", receptionistId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"active":false}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(receptionistId))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.status").value("INACTIVE"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"email":"recepcion@clinica.test","password":"%s"}
                """.formatted(PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reactivarCuentaDesactivadaRestauraElAcceso() throws Exception {
        staffInvitationService.setActive(receptionistId, false,
                new com.dentalclinic.identity.infrastructure.security.AuthenticatedUser(ownerId, Role.OWNER));

        mockMvc.perform(patch("/api/users/{id}/active", receptionistId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"active":true}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"email":"recepcion@clinica.test","password":"%s"}
                """.formatted(PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    void desactivarmeAMiMismoEs403() throws Exception {
        mockMvc.perform(patch("/api/users/{id}/active", ownerId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"active":false}
                """))
                .andExpect(status().isForbidden());
        assertThat(userRepository.findById(ownerId).orElseThrow().isActive()).isTrue();
    }

    @Test
    void desactivarCuentaOwnerEs403() throws Exception {
        // Solo hay un OWNER (P-07); cualquier intento de bajar la cuenta OWNER sobre sí misma
        // es 403. Con un segundo OWNER hypotheticalo la guarda de "último OWNER activo" también
        // rechazaría con 403; aquí el self-check corta antes, pero el resultado es el mismo.
        mockMvc.perform(patch("/api/users/{id}/active", ownerId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"active":false}
                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void desactivarPacienteEs403() throws Exception {
        mockMvc.perform(patch("/api/users/{id}/active", patientId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"active":false}
                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void desactivarCuentaPendienteEs403() throws Exception {
        Long pendiente = staffInvitationService.inviteReceptionist(
                new NewReceptionist("pendiente@clinica.test")).id();

        mockMvc.perform(patch("/api/users/{id}/active", pendiente)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"active":false}
                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void cambiarEstadoCuerpoInvalidoDevuelve400() throws Exception {
        mockMvc.perform(patch("/api/users/{id}/active", receptionistId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"active":null}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.active").isNotEmpty());

        mockMvc.perform(patch("/api/users/{id}/active", receptionistId)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cambiarEstadoCuentaInexistenteEs404() throws Exception {
        mockMvc.perform(patch("/api/users/{id}/active", 999_999L)
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"active":false}
                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void cambiarEstadoComoNoOwnerEs403() throws Exception {
        for (String token : new String[] {dentistToken, receptionistToken, patientToken}) {
            mockMvc.perform(patch("/api/users/{id}/active", receptionistId)
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                    {"active":false}
                    """))
                    .andExpect(status().isForbidden());
        }
        assertThat(userRepository.findById(receptionistId).orElseThrow().isActive()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private String loginAccessToken(String email) throws Exception {
        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                {"email":"%s","password":"%s"}
                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return new ObjectMapper().readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private Long seedUser(String email, Role role) {
        User user = userRepository.saveAndFlush(
                User.create(email, passwordEncoder.encode(PASSWORD), role));
        return user.getId();
    }
}

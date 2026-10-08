package com.dentalclinic.scheduling.api;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.dentalclinic.clinic.domain.Clinic;
import com.dentalclinic.clinic.domain.Dentist;
import com.dentalclinic.clinic.infrastructure.repository.ClinicRepository;
import com.dentalclinic.clinic.infrastructure.repository.DentistRepository;
import com.dentalclinic.identity.IdentityIntegrationTestSupport;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.infrastructure.repository.InvitationRepository;
import com.dentalclinic.identity.infrastructure.repository.PasswordResetTokenRepository;
import com.dentalclinic.identity.infrastructure.repository.RefreshSessionRepository;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;
import com.dentalclinic.scheduling.domain.ClinicBreak;
import com.dentalclinic.scheduling.domain.ClinicSchedule;
import com.dentalclinic.scheduling.domain.ClinicScheduleException;
import com.dentalclinic.scheduling.domain.DentistSchedule;
import com.dentalclinic.scheduling.domain.DentistTimeOff;
import com.dentalclinic.scheduling.infrastructure.repository.ClinicBreakRepository;
import com.dentalclinic.scheduling.infrastructure.repository.ClinicScheduleExceptionRepository;
import com.dentalclinic.scheduling.infrastructure.repository.ClinicScheduleRepository;
import com.dentalclinic.scheduling.infrastructure.repository.DentistScheduleRepository;
import com.dentalclinic.scheduling.infrastructure.repository.DentistTimeOffRepository;

/**
 * Disponibilidad calculada de Fase 5 (RF-AV-01..12). Verifica rejilla de 15 min, resta de breaks y
 * franquicias, duración no múltiplo de 15, duración mayor que la ventana, lead time, horizonte
 * máximo, excepciones cerradas y política DST conservadora con zona {@code Europe/Madrid}.
 */
@AutoConfigureMockMvc
class AvailabilityApiIntegrationTest extends IdentityIntegrationTestSupport {

    private static final String PASSWORD = "ClaveDisponible-2026";
    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 6);

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
    private PasswordEncoder passwordEncoder;
    @Autowired
    private ClinicRepository clinicRepository;
    @Autowired
    private DentistRepository dentistRepository;
    @Autowired
    private ClinicScheduleRepository clinicScheduleRepository;
    @Autowired
    private ClinicBreakRepository clinicBreakRepository;
    @Autowired
    private ClinicScheduleExceptionRepository clinicExceptionRepository;
    @Autowired
    private DentistScheduleRepository dentistScheduleRepository;
    @Autowired
    private DentistTimeOffRepository timeOffRepository;

    @BeforeEach
    void cleanSlate() {
        TestTime.reset();
        refreshSessionRepository.deleteAll();
        resetTokenRepository.deleteAll();
        invitationRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void generaIniciosCadaQuinceMinutos() throws Exception {
        Fixture f = fixture("UTC");
        String token = loginToken(seedUser("own@c.com", Role.OWNER));

        mockMvc.perform(get("/api/availability").param("dentistId", f.dentistId().toString())
                        .param("from", TUESDAY.toString()).param("duration", "30")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("UTC"))
                .andExpect(jsonPath("$.slots", hasSize(11)))
                .andExpect(jsonPath("$.slots[0].startTime").value("09:00:00"))
                .andExpect(jsonPath("$.slots[0].start").value("2026-10-06T09:00:00Z"))
                .andExpect(jsonPath("$.slots[10].startTime").value("11:30:00"));
    }

    @Test
    void restaBreaksDeClinicYFranquiciasDelDentista() throws Exception {
        Fixture f = fixture("UTC");
        clinicBreakRepository.saveAndFlush(ClinicBreak.create(f.clinicId(), 2, LocalTime.of(10, 0),
                LocalTime.of(10, 30)));
        timeOffRepository.saveAndFlush(DentistTimeOff.create(f.dentistId(), TUESDAY, TUESDAY,
                LocalTime.of(11, 0), LocalTime.of(11, 30), "Privado"));
        String token = loginToken(seedUser("own@c.com", Role.OWNER));

        mockMvc.perform(get("/api/availability").param("dentistId", f.dentistId().toString())
                        .param("from", TUESDAY.toString()).param("duration", "30")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots", hasSize(5)))
                .andExpect(jsonPath("$.slots[0].startTime").value("09:00:00"))
                .andExpect(jsonPath("$.slots[1].startTime").value("09:15:00"))
                .andExpect(jsonPath("$.slots[2].startTime").value("09:30:00"))
                .andExpect(jsonPath("$.slots[3].startTime").value("10:30:00"))
                .andExpect(jsonPath("$.slots[4].startTime").value("11:30:00"));
    }

    @Test
    void duracionNoMultiploDeQuinceSigueLaRejilla() throws Exception {
        Fixture f = fixture("UTC");
        String token = loginToken(seedUser("own@c.com", Role.OWNER));
        mockMvc.perform(get("/api/availability").param("dentistId", f.dentistId().toString())
                        .param("from", TUESDAY.toString()).param("duration", "20")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots", hasSize(11)))
                .andExpect(jsonPath("$.slots[0].startTime").value("09:00:00"))
                .andExpect(jsonPath("$.slots[0].endTime").value("09:20:00"))
                .andExpect(jsonPath("$.slots[1].startTime").value("09:15:00"));
    }

    @Test
    void duracionMayorQueLaVentanaNoProduceSlots() throws Exception {
        Fixture f = fixture("UTC");
        String token = loginToken(seedUser("own@c.com", Role.OWNER));
        mockMvc.perform(get("/api/availability").param("dentistId", f.dentistId().toString())
                        .param("from", TUESDAY.toString()).param("duration", "240")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots", hasSize(0)));
    }

    @Test
    void elLeadTimeMinimoRecortaLosInicios() throws Exception {
        Fixture f = fixture("UTC", 2, 90);
        String token = loginToken(seedUser("own@c.com", Role.OWNER));
        mockMvc.perform(get("/api/availability").param("dentistId", f.dentistId().toString())
                        .param("from", TUESDAY.toString()).param("duration", "30")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots", hasSize(7)))
                .andExpect(jsonPath("$.slots[0].startTime").value("10:00:00"))
                .andExpect(jsonPath("$.slots[0].start").value("2026-10-06T10:00:00Z"));
    }

    @Test
    void elHorizonteMaximoAcotaElRango() throws Exception {
        Fixture f = fixture("UTC", 0, 0);
        String token = loginToken(seedUser("own@c.com", Role.OWNER));
        mockMvc.perform(get("/api/availability").param("dentistId", f.dentistId().toString())
                        .param("from", TUESDAY.toString()).param("to", TUESDAY.plusDays(30).toString())
                        .param("duration", "30").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots", hasSize(11)))
                .andExpect(jsonPath("$.slots[0].startDate").value("2026-10-06"))
                .andExpect(jsonPath("$.slots[10].startDate").value("2026-10-06"));
    }

    @Test
    void unaExcepcionCerradaAnulaElDia() throws Exception {
        Fixture f = fixture("UTC");
        clinicExceptionRepository.saveAndFlush(ClinicScheduleException.create(f.clinicId(), TUESDAY,
                true, null, null, "Feriado"));
        String token = loginToken(seedUser("own@c.com", Role.OWNER));
        mockMvc.perform(get("/api/availability").param("dentistId", f.dentistId().toString())
                        .param("from", TUESDAY.toString()).param("duration", "30")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots", hasSize(0)));
    }

    @Test
    void politicaDstConservadoraEnMadrid() throws Exception {
        Fixture f = fixture("Europe/Madrid");
        TestTime.advance(Duration.between(TestTime.BASE, Instant.parse("2027-03-25T08:00:00Z")));
        String token = loginToken(seedUser("own@c.com", Role.OWNER));
        LocalDate dstSunday = LocalDate.of(2027, 3, 28);
        clinicScheduleRepository.deleteAll();
        dentistScheduleRepository.deleteAll();
        clinicScheduleRepository.saveAndFlush(ClinicSchedule.create(f.clinicId(), 7, LocalTime.of(1, 0),
                LocalTime.of(5, 0)));
        dentistScheduleRepository.saveAndFlush(DentistSchedule.create(f.dentistId(), 7,
                LocalTime.of(1, 0), LocalTime.of(5, 0)));

        mockMvc.perform(get("/api/availability").param("dentistId", f.dentistId().toString())
                        .param("from", dstSunday.toString()).param("duration", "30")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots", hasSize(9)))
                .andExpect(jsonPath("$.slots[0].startTime").value("01:00:00"))
                .andExpect(jsonPath("$.slots[1].startTime").value("01:15:00"))
                .andExpect(jsonPath("$.slots[2].startTime").value("03:00:00"))
                .andExpect(jsonPath("$.slots[6].startTime").value("04:00:00"))
                .andExpect(jsonPath("$.slots[6].start").value("2027-03-28T02:00:00Z"));
    }

    // ===== Helpers =====

    private record Fixture(Long clinicId, Long dentistId) {
    }

    private Fixture fixture(String timezone) {
        return fixture(timezone, 0, 90);
    }

    private Fixture fixture(String timezone, int leadHours, int maxAdvanceDays) {
        Clinic clinic = Clinic.create("Clínica", timezone, null, null, null, null, null, null, null, null);
        clinic.updateBookingPolicy(maxAdvanceDays, leadHours, 12, Instant.now());
        clinic = clinicRepository.saveAndFlush(clinic);
        clinicScheduleRepository.saveAndFlush(ClinicSchedule.create(clinic.getId(), 2, LocalTime.of(9, 0),
                LocalTime.of(12, 0)));
        User user = seedUser("dentist@c.com", Role.DENTIST);
        Dentist dentist = dentistRepository.saveAndFlush(Dentist.create(user.getId(), "Dent", "Uno",
                "600000000", null, null, null, null));
        dentistScheduleRepository.saveAndFlush(DentistSchedule.create(dentist.getId(), 2,
                LocalTime.of(9, 0), LocalTime.of(12, 0)));
        return new Fixture(clinic.getId(), dentist.getId());
    }

    private String loginToken(User user) throws Exception {
        var result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(user.getEmail(), PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private User seedUser(String email, Role role) {
        return userRepository.findByEmail(email)
                .orElseGet(() -> userRepository.saveAndFlush(
                        User.create(email, passwordEncoder.encode(PASSWORD), role)));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
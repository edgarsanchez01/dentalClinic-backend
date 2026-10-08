package com.dentalclinic.scheduling.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.dentalclinic.scheduling.api.dto.TimeRangeRequest;
import com.dentalclinic.scheduling.api.dto.WeekDayRequest;
import com.dentalclinic.scheduling.api.dto.WeekRequest;
import com.dentalclinic.scheduling.application.ClinicScheduleService;
import com.dentalclinic.scheduling.domain.ClinicBreak;
import com.dentalclinic.scheduling.domain.ClinicSchedule;
import com.dentalclinic.scheduling.domain.ClinicScheduleException;
import com.dentalclinic.scheduling.domain.DentistBreak;
import com.dentalclinic.scheduling.domain.DentistSchedule;
import com.dentalclinic.scheduling.domain.DentistTimeOff;
import com.dentalclinic.scheduling.infrastructure.repository.ClinicBreakRepository;
import com.dentalclinic.scheduling.infrastructure.repository.ClinicScheduleExceptionRepository;
import com.dentalclinic.scheduling.infrastructure.repository.ClinicScheduleRepository;
import com.dentalclinic.scheduling.infrastructure.repository.DentistBreakRepository;
import com.dentalclinic.scheduling.infrastructure.repository.DentistScheduleRepository;
import com.dentalclinic.scheduling.infrastructure.repository.DentistTimeOffRepository;

/**
 * Auditoría adversarial de Fase 5. Cubre lo que el contrato feliz no ejercita: atomicidad real del
 * reemplazo semanal ante un fallo de motor de base de datos, invariantes de contención en updates
 * parciales, validaciones exhaustivas de franquicias con IDOR, resta de breaks bajo ventanas
 * especiales, duración no múltiplo de 15, motor único de disponibilidad por rol y no fuga en el
 * horario público.
 */
@AutoConfigureMockMvc
class SchedulingAdversarialAuditTest extends IdentityIntegrationTestSupport {

    private static final String PASSWORD = "ClaveAudit-2026";
    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 6);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
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
    private DentistBreakRepository dentistBreakRepository;
    @Autowired
    private DentistTimeOffRepository timeOffRepository;
    @Autowired
    private ClinicScheduleService clinicScheduleService;

    @BeforeEach
    void cleanSlate() {
        TestTime.reset();
        refreshSessionRepository.deleteAll();
        resetTokenRepository.deleteAll();
        invitationRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ===== Regresión: reloj de pared sin desplazamiento por zona del JVM =====

    @Test
    void horasYFechasSePersistenComoRelojDeParedAunqueElJvmNoSeaUtc() {
        Clinic c = seedClinicRow("UTC");
        clinicScheduleService.replaceWeek(week(9, 12, LocalTime.of(10, 0)));

        List<String> starts = jdbcTemplate.queryForList(
                "SELECT start_time::text FROM clinic_break WHERE clinic_id = ? ORDER BY day_of_week",
                String.class, c.getId());
        assertThat(starts).hasSize(7).allMatch("10:00:00"::equals);

        clinicExceptionRepository.saveAndFlush(ClinicScheduleException.create(c.getId(), TUESDAY,
                true, null, null, null));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT exception_date::text FROM clinic_schedule_exception WHERE clinic_id = ?",
                String.class, c.getId())).isEqualTo("2026-10-06");
    }

    // ===== J. Duración =====

    @Test
    void duracionCeroNegativaOMayorQueLaVentana() throws Exception {
        Fixture f = fixture("UTC", 0, 90, "09:00", "12:00");
        String token = loginToken(seedUser("ownj@c.com", Role.OWNER));

        for (String bad : List.of("0", "-15")) {
            mockMvc.perform(get("/api/availability").param("dentistId", f.dentistId().toString())
                            .param("from", TUESDAY.toString()).param("duration", bad)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/availability").param("dentistId", f.dentistId().toString())
                        .param("from", TUESDAY.toString()).param("duration", "100000")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots", hasSize(0)));
    }

    @Test
    void duracionCincuentaNoSeRechazaYRespetaLaRejilla() throws Exception {
        Fixture f = fixture("UTC", 0, 90, "09:00", "12:00");
        String token = loginToken(seedUser("ownj2@c.com", Role.OWNER));
        mockMvc.perform(get("/api/availability").param("dentistId", f.dentistId().toString())
                        .param("from", TUESDAY.toString()).param("duration", "50")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots", hasSize(9)))
                .andExpect(jsonPath("$.slots[0].startTime").value("09:00:00"))
                .andExpect(jsonPath("$.slots[0].endTime").value("09:50:00"))
                .andExpect(jsonPath("$.slots[8].startTime").value("11:00:00"));
    }

    // ===== H. Exceptions + breaks =====

    @Test
    void excepcionEspecialSigueRestandoBreaksSemanales() throws Exception {
        Fixture f = fixture("UTC", 0, 90, "09:00", "17:00");
        clinicBreakRepository.saveAndFlush(ClinicBreak.create(f.clinicId(), 2, LocalTime.of(13, 0),
                LocalTime.of(14, 0)));
        clinicExceptionRepository.saveAndFlush(ClinicScheduleException.create(f.clinicId(), TUESDAY,
                false, LocalTime.of(10, 0), LocalTime.of(16, 0), null));
        String token = loginToken(seedUser("ownh@c.com", Role.OWNER));
        String body = availabilityBody(f, token);
        assertThat(body).doesNotContain("\"startTime\":\"13:00:00\"");
        assertThat(body).contains("\"startTime\":\"12:00:00\"");
        assertThat(body).contains("\"startTime\":\"14:00:00\"");
    }

    @Test
    void breakParcialmenteFueraDeVentanaEspecialSoloRestaLaInterseccion() throws Exception {
        Fixture f = fixture("UTC", 0, 90, "09:00", "17:00");
        clinicBreakRepository.saveAndFlush(ClinicBreak.create(f.clinicId(), 2, LocalTime.of(9, 30),
                LocalTime.of(10, 30)));
        clinicExceptionRepository.saveAndFlush(ClinicScheduleException.create(f.clinicId(), TUESDAY,
                false, LocalTime.of(10, 0), LocalTime.of(16, 0), null));
        String token = loginToken(seedUser("ownh2@c.com", Role.OWNER));
        String body = availabilityBody(f, token);
        assertThat(body).doesNotContain("\"startTime\":\"10:00:00\"");
        assertThat(body).contains("\"startTime\":\"10:30:00\"");
    }

    @Test
    void excepcionCerradaAnulaAunqueHayaBreaks() throws Exception {
        Fixture f = fixture("UTC", 0, 90, "09:00", "17:00");
        clinicBreakRepository.saveAndFlush(ClinicBreak.create(f.clinicId(), 2, LocalTime.of(13, 0),
                LocalTime.of(14, 0)));
        clinicExceptionRepository.saveAndFlush(ClinicScheduleException.create(f.clinicId(), TUESDAY,
                true, null, null, "Feriado"));
        String token = loginToken(seedUser("ownh3@c.com", Role.OWNER));
        mockMvc.perform(get("/api/availability").param("dentistId", f.dentistId().toString())
                        .param("from", TUESDAY.toString()).param("duration", "30")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots", hasSize(0)));
    }

    // ===== I. Dentist ⊆ Clinic =====

    @Test
    void updateParcialInvalidoNoPersisteNada() throws Exception {
        Fixture f = fixture("UTC", 0, 90, "09:00", "17:00");
        String token = loginToken(seedUser("owni@c.com", Role.OWNER));
        mockMvc.perform(put("/api/schedules/dentist/" + f.dentistId() + "/day/1")
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"open\":true,\"startTime\":\"08:00:00\",\"endTime\":\"16:00:00\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/schedules/dentist/" + f.dentistId())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].open").value(false));
    }

    @Test
    void clinicCerradaBloqueaExcepcionDeDentista() throws Exception {
        Fixture f = fixture("UTC", 0, 90, "09:00", "17:00");
        String token = loginToken(seedUser("owni2@c.com", Role.OWNER));
        mockMvc.perform(put("/api/schedules/clinic").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(weekAllClosed()))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/schedules/dentist/" + f.dentistId() + "/exceptions/" + TUESDAY)
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"closed\":false,\"startTime\":\"09:00:00\",\"endTime\":\"10:00:00\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void excepcionDeDentistaNoPuedeExcederVentanaEspecialDeClinic() throws Exception {
        Fixture f = fixture("UTC", 0, 90, "09:00", "17:00");
        String token = loginToken(seedUser("owni2b@c.com", Role.OWNER));
        clinicExceptionRepository.saveAndFlush(ClinicScheduleException.create(f.clinicId(), TUESDAY,
                false, LocalTime.of(10, 0), LocalTime.of(15, 0), null));
        mockMvc.perform(put("/api/schedules/dentist/" + f.dentistId() + "/exceptions/" + TUESDAY)
                        .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"closed\":false,\"startTime\":\"09:00:00\",\"endTime\":\"16:00:00\"}"))
                .andExpect(status().isConflict());
    }

    // ===== E. TimeOff =====

    @Test
    void timeOffValidacionesEstructurales() throws Exception {
        Fixture f = fixture("UTC", 0, 90, "09:00", "12:00");
        String token = loginToken(seedUser("owne@c.com", Role.OWNER));
        String base = "/api/schedules/dentist/" + f.dentistId() + "/time-off";

        mockMvc.perform(post(base).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-10-13\",\"endDate\":\"2026-10-13\","
                                + "\"startTime\":\"10:00:00\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(base).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-10-13\",\"endDate\":\"2026-10-13\","
                                + "\"endTime\":\"11:00:00\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(base).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-10-13\",\"endDate\":\"2026-10-13\","
                                + "\"startTime\":\"11:00:00\",\"endTime\":\"11:00:00\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(base).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-10-14\",\"endDate\":\"2026-10-13\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(base).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-10-13\",\"endDate\":\"2026-10-14\","
                                + "\"startTime\":\"10:00:00\",\"endTime\":\"11:00:00\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(base).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-10-13\",\"endDate\":\"2026-10-13\","
                                + "\"startTime\":\"10:00:00\",\"endTime\":\"11:00:00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allDay").value(false));
        mockMvc.perform(post(base).header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-10-15\",\"endDate\":\"2026-10-15\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allDay").value(true));
    }

    @Test
    void timeOffIdorEntreDentistas() throws Exception {
        User ua = seedUser("a@c.com", Role.DENTIST);
        User ub = seedUser("b@c.com", Role.DENTIST);
        Dentist da = dentistRepository.saveAndFlush(Dentist.create(ua.getId(), "A", "Uno",
                "600000000", null, null, null, null));
        Dentist db = dentistRepository.saveAndFlush(Dentist.create(ub.getId(), "B", "Dos",
                "600000001", null, null, null, null));
        seedClinicRow("UTC");
        DentistTimeOff off = timeOffRepository.saveAndFlush(DentistTimeOff.create(db.getId(), TUESDAY,
                TUESDAY, null, null, "privado"));

        String ta = loginToken(ua);
        mockMvc.perform(get("/api/schedules/dentist/" + db.getId() + "/time-off")
                        .header("Authorization", bearer(ta)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/schedules/dentist/" + da.getId() + "/time-off")
                        .header("Authorization", bearer(ta)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(post("/api/schedules/dentist/" + db.getId() + "/time-off")
                        .header("Authorization", bearer(ta)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-10-13\",\"endDate\":\"2026-10-13\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/schedules/dentist/" + da.getId() + "/time-off/" + off.getId())
                        .header("Authorization", bearer(ta)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDate\":\"2026-10-13\",\"endDate\":\"2026-10-13\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/schedules/dentist/" + db.getId() + "/time-off/" + off.getId())
                        .header("Authorization", bearer(ta)))
                .andExpect(status().isNotFound());
        // A no puede operar el scheduling de B.
        mockMvc.perform(get("/api/schedules/dentist/" + db.getId())
                        .header("Authorization", bearer(ta)))
                .andExpect(status().isNotFound());
    }

    // ===== N. Motor único =====

    @Test
    void todosLosRolesInternosYPatientRecibenLaMismaDisponibilidad() throws Exception {
        Fixture f = fixture("UTC", 0, 90, "09:00", "12:00");
        String owner = loginToken(seedUser("ownn@c.com", Role.OWNER));
        String reception = loginToken(seedUser("recn@c.com", Role.RECEPTIONIST));
        String patient = loginToken(seedUser("patn@c.com", Role.PATIENT));

        String ownerBody = availabilityBody(f, owner);
        assertThat(availabilityBody(f, reception)).isEqualTo(ownerBody);
        assertThat(availabilityBody(f, patient)).isEqualTo(ownerBody);
    }

    // ===== M. Fuga de datos públicos =====

    @Test
    void elHorarioPublicoSoloExponeLosCamposAprobados() throws Exception {
        Fixture f = fixture("UTC", 0, 90, "09:00", "17:00");
        clinicExceptionRepository.saveAndFlush(ClinicScheduleException.create(f.clinicId(), TUESDAY,
                true, null, null, "Motivo privado"));

        mockMvc.perform(get("/api/public/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].breaks").doesNotExist())
                .andExpect(jsonPath("$.dentists[0].phone").doesNotExist())
                .andExpect(jsonPath("$.dentists[0].licenseNumber").doesNotExist())
                .andExpect(jsonPath("$.dentists[0].publicInfo").doesNotExist())
                .andExpect(content().string(not(containsString("Motivo privado"))))
                .andExpect(content().string(not(containsString("licenseNumber"))))
                .andExpect(content().string(not(containsString("\"breaks\""))));
    }

    // ===== D. Policy persistida y usada por disponibilidad =====

    @Test
    void laPoliticaDeReservasSePersisteYLaUsaLaDisponibilidad() throws Exception {
        Fixture f = fixture("UTC", 0, 90, "09:00", "12:00");
        String token = loginToken(seedUser("ownd@c.com", Role.OWNER));
        mockMvc.perform(put("/api/availability/policy").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxAdvanceDays\":30,\"minBookingLeadTimeHours\":2,"
                                + "\"cancellationLeadTimeHours\":6}"))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("SELECT max_advance_days FROM clinic WHERE id = ?",
                Integer.class, f.clinicId())).isEqualTo(30);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT min_booking_lead_time_hours FROM clinic WHERE id = ?", Integer.class,
                f.clinicId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT cancellation_lead_time_hours FROM clinic WHERE id = ?", Integer.class,
                f.clinicId())).isEqualTo(6);

        mockMvc.perform(get("/api/availability").param("dentistId", f.dentistId().toString())
                        .param("from", TUESDAY.toString()).param("duration", "30")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slots", hasSize(7)))
                .andExpect(jsonPath("$.slots[0].startTime").value("10:00:00"));
    }

    // ===== G. Atomicidad del reemplazo semanal =====

    @Test
    void elReemplazoSemanalDeDentistaEsAtomicoAnteUnFalloDeBase() throws Exception {
        seedClinicRow("UTC");
        clinicScheduleService.replaceWeek(week(9, 12, null));
        String owner = loginToken(seedUser("atomicowner@c.com", Role.OWNER));
        User u = seedUser("atomicd@c.com", Role.DENTIST);
        Dentist d = dentistRepository.saveAndFlush(Dentist.create(u.getId(), "Atom", "Dent",
                "600000000", null, null, null, null));
        String path = "/api/schedules/dentist/" + d.getId();

        putWeek(path, owner, week(9, 12, LocalTime.of(10, 0))).andExpect(status().isOk());
        assertThat(dentistScheduleRepository.findByDentistIdOrderByDayOfWeekAsc(d.getId())).hasSize(7);
        assertThat(dentistBreakRepository.findByDentistIdOrderByDayOfWeekAscStartTimeAsc(d.getId()))
                .hasSize(7);

        try {
            jdbcTemplate.execute("ALTER TABLE dentist_break ADD CONSTRAINT audit_dentist_no_1111 "
                    + "CHECK (start_time <> TIME '11:11')");
            putWeek(path, owner, week(9, 12, LocalTime.of(11, 11)))
                    .andExpect(status().isConflict());
        } finally {
            jdbcTemplate.execute(
                    "ALTER TABLE dentist_break DROP CONSTRAINT IF EXISTS audit_dentist_no_1111");
        }

        List<DentistBreak> breaks = dentistBreakRepository
                .findByDentistIdOrderByDayOfWeekAscStartTimeAsc(d.getId());
        assertThat(dentistScheduleRepository.findByDentistIdOrderByDayOfWeekAsc(d.getId())).hasSize(7);
        assertThat(breaks).hasSize(7);
        assertThat(breaks).allMatch(b -> !b.getStartTime().equals(LocalTime.of(11, 11)));
    }

    @Test
    void elReemplazoSemanalDeClinicEsAtomicoAnteUnFalloDeBase() throws Exception {
        Clinic clinic = seedClinicRow("UTC");
        String owner = loginToken(seedUser("atomicowner2@c.com", Role.OWNER));
        clinicScheduleService.replaceWeek(week(9, 12, LocalTime.of(10, 0)));
        assertThat(clinicBreakRepository.findByClinicIdOrderByDayOfWeekAscStartTimeAsc(clinic.getId()))
                .hasSize(7);

        try {
            jdbcTemplate.execute("ALTER TABLE clinic_break ADD CONSTRAINT audit_clinic_no_1111 "
                    + "CHECK (start_time <> TIME '11:11')");
            putWeek("/api/schedules/clinic", owner, week(9, 12, LocalTime.of(11, 11)))
                    .andExpect(status().isConflict());
        } finally {
            jdbcTemplate.execute(
                    "ALTER TABLE clinic_break DROP CONSTRAINT IF EXISTS audit_clinic_no_1111");
        }

        List<ClinicBreak> breaks = clinicBreakRepository
                .findByClinicIdOrderByDayOfWeekAscStartTimeAsc(clinic.getId());
        assertThat(breaks).hasSize(7);
        assertThat(breaks).allMatch(b -> !b.getStartTime().equals(LocalTime.of(11, 11)));
    }

    // ===== Helpers =====

    private record Fixture(Long clinicId, Long dentistId) {
    }

    private Fixture fixture(String tz, int lead, int advance, String start, String end) {
        Clinic c = seedClinicRow(tz);
        c.updateBookingPolicy(advance, lead, 12, Instant.now());
        clinicRepository.saveAndFlush(c);
        LocalTime s = LocalTime.parse(start);
        LocalTime e = LocalTime.parse(end);
        clinicScheduleRepository.saveAndFlush(ClinicSchedule.create(c.getId(), 2, s, e));
        User u = seedUser("dentistfixture@c.com", Role.DENTIST);
        Dentist d = dentistRepository.saveAndFlush(Dentist.create(u.getId(), "Dent", "Ist",
                "600000000", null, null, null, null));
        dentistScheduleRepository.saveAndFlush(DentistSchedule.create(d.getId(), 2, s, e));
        return new Fixture(c.getId(), d.getId());
    }

    private Clinic seedClinicRow(String tz) {
        return clinicRepository.saveAndFlush(Clinic.create("Clínica", tz, null, null, null, null,
                null, null, null, null));
    }

    private ResultActions putWeek(String path, String token, WeekRequest request) throws Exception {
        return mockMvc.perform(put(path).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private String availabilityBody(Fixture f, String token) throws Exception {
        return mockMvc.perform(get("/api/availability").param("dentistId", f.dentistId().toString())
                        .param("from", TUESDAY.toString()).param("duration", "30")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** Semana completa abierta con, opcionalmente, un break desde {@code breakStart} + 15m. */
    private static WeekRequest week(int startHour, int endHour, LocalTime breakStart) {
        List<WeekDayRequest> days = new ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            List<TimeRangeRequest> breaks = breakStart == null ? List.of()
                    : List.of(new TimeRangeRequest(breakStart, breakStart.plusMinutes(15)));
            days.add(new WeekDayRequest(d, true, LocalTime.of(startHour, 0),
                    LocalTime.of(endHour, 0), breaks));
        }
        return new WeekRequest(days);
    }

    private static String weekAllClosed() {
        StringBuilder sb = new StringBuilder("{\"days\":[");
        for (int d = 1; d <= 7; d++) {
            if (d > 1) {
                sb.append(',');
            }
            sb.append("{\"dayOfWeek\":").append(d).append(",\"open\":false,\"breaks\":[]}");
        }
        return sb.append("]}").toString();
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
        return userRepository.findByEmail(email).orElseGet(() -> userRepository.saveAndFlush(
                User.create(email, passwordEncoder.encode(PASSWORD), role)));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
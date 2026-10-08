package com.dentalclinic.scheduling.api;

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

import java.util.function.IntFunction;

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

/**
 * Contrato HTTP del Scheduling de Fase 5: horarios/breaks/excepciones de clínica y dentista,
 * franquicias (time-off), política de reservas y horario público. Verifica la matriz de permisos
 * §7.4 (OWNER escribe; DENTIST self; RECEPTIONIST lee; PATIENT sin acceso) y las invariantes de
 * contención (409) y validación estructural (400).
 */
@AutoConfigureMockMvc
class SchedulingApiIntegrationTest extends IdentityIntegrationTestSupport {

    private static final String PASSWORD = "ClaveScheduling-2026";
    private static final String DATE = "2026-10-12";

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

    @BeforeEach
    void cleanSlate() {
        TestTime.reset();
        refreshSessionRepository.deleteAll();
        resetTokenRepository.deleteAll();
        invitationRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ===== Horario de la clínica =====

    @Test
    void ownerReemplazaLaSemanaYLeeSieteDias() throws Exception {
        seedClinic("UTC");
        String token = loginToken(seedUser("own@c.com", Role.OWNER));

        mockMvc.perform(put("/api/schedules/clinic").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weekBody(d -> d <= 5
                                ? open(d, "09:00:00", "17:00:00", d == 1 ? "13:00:00" : null, "14:00:00")
                                : closed(d))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("UTC"))
                .andExpect(jsonPath("$.days", hasSize(7)))
                .andExpect(jsonPath("$.days[0].dayOfWeek").value(1))
                .andExpect(jsonPath("$.days[0].startTime").value("09:00:00"))
                .andExpect(jsonPath("$.days[0].breaks", hasSize(1)))
                .andExpect(jsonPath("$.days[5].open").value(false));

        mockMvc.perform(get("/api/schedules/clinic").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days", hasSize(7)))
                .andExpect(jsonPath("$.days[0].breaks", hasSize(1)));
    }

    @Test
    void dentistaNoPuedeEscribirLaClinicPeroLee() throws Exception {
        seedClinic("UTC");
        String token = loginToken(seedUser("den@c.com", Role.DENTIST));
        mockMvc.perform(get("/api/schedules/clinic").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/schedules/clinic").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(weekAllClosed()))
                .andExpect(status().isForbidden());
    }

    @Test
    void semanaIncompleta400YBreakFueraDeVentana400() throws Exception {
        seedClinic("UTC");
        String token = loginToken(seedUser("own2@c.com", Role.OWNER));
        mockMvc.perform(put("/api/schedules/clinic").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\":[{\"dayOfWeek\":1,\"open\":true,"
                                + "\"startTime\":\"09:00:00\",\"endTime\":\"17:00:00\"}]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/schedules/clinic").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weekBody(d -> d == 1
                                ? "{\"dayOfWeek\":1,\"open\":true,\"startTime\":\"09:00:00\","
                                        + "\"endTime\":\"10:00:00\",\"breaks\":[{\"startTime\":\"10:00:00\","
                                        + "\"endTime\":\"11:00:00\"}]}"
                                : closed(d))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void breaksSolapados409() throws Exception {
        seedClinic("UTC");
        String token = loginToken(seedUser("own3@c.com", Role.OWNER));
        String monday = "{\"dayOfWeek\":1,\"open\":true,\"startTime\":\"09:00:00\","
                + "\"endTime\":\"17:00:00\",\"breaks\":["
                + "{\"startTime\":\"10:00:00\",\"endTime\":\"11:00:00\"},"
                + "{\"startTime\":\"10:30:00\",\"endTime\":\"11:30:00\"}]}";
        mockMvc.perform(put("/api/schedules/clinic").header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\":[" + monday + "," + closed(2) + "," + closed(3) + ","
                                + closed(4) + "," + closed(5) + "," + closed(6) + "," + closed(7) + "]}"))
                .andExpect(status().isConflict());
    }

    @Test
    void excepcionSeReemplazaYSeBorra() throws Exception {
        seedClinic("UTC");
        String token = loginToken(seedUser("own3@c.com", Role.OWNER));
        mockMvc.perform(put("/api/schedules/clinic/exceptions/" + DATE)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"closed":true,"reason":"Feriado"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(true))
                .andExpect(jsonPath("$.reason").value("Feriado"));
        mockMvc.perform(get("/api/schedules/clinic/exceptions").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(delete("/api/schedules/clinic/exceptions/" + DATE)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/schedules/clinic/exceptions").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ===== Horario del dentista =====

    @Test
    void dentistaGestionaSoloLoPropioYOwnerCualquiera() throws Exception {
        seedClinic("UTC");
        User u1 = seedUser("den1@c.com", Role.DENTIST);
        User u2 = seedUser("den2@c.com", Role.DENTIST);
        Dentist d1 = seedDentist(u1, "Ana", "Uno");
        Dentist d2 = seedDentist(u2, "Beto", "Dos");

        String t1 = loginToken(u1);
        mockMvc.perform(get("/api/schedules/dentist/" + d1.getId()).header("Authorization", bearer(t1)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/schedules/dentist/" + d2.getId()).header("Authorization", bearer(t1)))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/schedules/dentist/" + d2.getId()).header("Authorization", bearer(t1))
                        .contentType(MediaType.APPLICATION_JSON).content(dentistWeekOpen()))
                .andExpect(status().isNotFound());

        String owner = loginToken(seedUser("own3@c.com", Role.OWNER));
        mockMvc.perform(put("/api/schedules/clinic").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weekBody(day -> openDay(day, "09:00:00", "17:00:00"))))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/schedules/dentist/" + d2.getId()).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content(dentistWeekOpen()))
                .andExpect(status().isOk());
    }

    @Test
    void dentistaFueraDeLaVentanaDeClinic409() throws Exception {
        seedClinic("UTC");
        User u = seedUser("den3@c.com", Role.DENTIST);
        Dentist d = seedDentist(u, "Carla", "Tres");
        String owner = loginToken(seedUser("own4@c.com", Role.OWNER));
        mockMvc.perform(put("/api/schedules/clinic").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weekBody(day -> openDay(day, "09:00:00", "17:00:00"))))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/schedules/dentist/" + d.getId()).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weekBody(day -> openDay(day, "08:00:00", "18:00:00"))))
                .andExpect(status().isConflict());
    }

    @Test
    void dentistExceptionNoExcedeLaVentanaEspecialDeClinic() throws Exception {
        seedClinic("UTC");
        User u = seedUser("den11@c.com", Role.DENTIST);
        Dentist d = seedDentist(u, "Iris", "Once");
        String owner = loginToken(seedUser("own9@c.com", Role.OWNER));
        mockMvc.perform(put("/api/schedules/clinic").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weekBody(day -> openDay(day, "09:00:00", "17:00:00"))))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/schedules/clinic/exceptions/" + DATE)
                        .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"closed":false,"startTime":"09:00:00","endTime":"12:00:00"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/schedules/dentist/" + d.getId() + "/exceptions/" + DATE)
                        .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"closed":false,"startTime":"09:00:00","endTime":"14:00:00"}
                                """))
                .andExpect(status().isConflict());
        mockMvc.perform(put("/api/schedules/dentist/" + d.getId() + "/exceptions/" + DATE)
                        .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"closed":false,"startTime":"10:00:00","endTime":"11:00:00"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void receptionistSoloLee() throws Exception {
        seedClinic("UTC");
        User u = seedUser("den5@c.com", Role.DENTIST);
        Dentist d = seedDentist(u, "Dora", "Cinco");
        String rec = loginToken(seedUser("rec@c.com", Role.RECEPTIONIST));
        mockMvc.perform(get("/api/schedules/dentist/" + d.getId()).header("Authorization", bearer(rec)))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/schedules/dentist/" + d.getId()).header("Authorization", bearer(rec))
                        .contentType(MediaType.APPLICATION_JSON).content(dentistWeekOpen()))
                .andExpect(status().isForbidden());
    }

    // ===== Time-off =====

    @Test
    void timeOffParcialDebeSerDeUnSoloDiaYElAllDayAdmiteRango() throws Exception {
        seedClinic("UTC");
        User u = seedUser("den6@c.com", Role.DENTIST);
        Dentist d = seedDentist(u, "Eva", "Seis");
        String owner = loginToken(seedUser("own5@c.com", Role.OWNER));

        mockMvc.perform(post("/api/schedules/dentist/" + d.getId() + "/time-off")
                        .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startDate":"2026-10-12","endDate":"2026-10-13",
                                 "startTime":"10:00:00","endTime":"11:00:00"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/schedules/dentist/" + d.getId() + "/time-off")
                        .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startDate":"2026-10-12","endDate":"2026-10-13"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allDay").value(true));
    }

    @Test
    void timeOffDeOtroDentistaResponde404() throws Exception {
        seedClinic("UTC");
        User u1 = seedUser("den7@c.com", Role.DENTIST);
        User u2 = seedUser("den8@c.com", Role.DENTIST);
        Dentist d1 = seedDentist(u1, "F", "Uno");
        Dentist d2 = seedDentist(u2, "G", "Dos");
        String owner = loginToken(seedUser("own6@c.com", Role.OWNER));

        var result = mockMvc.perform(post("/api/schedules/dentist/" + d1.getId() + "/time-off")
                        .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startDate":"2026-10-12","endDate":"2026-10-12"}
                                """))
                .andExpect(status().isCreated()).andReturn();
        long id = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(delete("/api/schedules/dentist/" + d2.getId() + "/time-off/" + id)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isNotFound());
    }

    // ===== Política de reservas =====

    @Test
    void policySeLeeEnTodosLosRolesYSeEscribeSoloComoOwner() throws Exception {
        seedClinic("UTC");
        String den = loginToken(seedUser("den9@c.com", Role.DENTIST));
        mockMvc.perform(get("/api/availability/policy").header("Authorization", bearer(den)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxAdvanceDays").value(90));
        mockMvc.perform(put("/api/availability/policy").header("Authorization", bearer(den))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"maxAdvanceDays":30,"minBookingLeadTimeHours":1,
                                 "cancellationLeadTimeHours":6}
                                """))
                .andExpect(status().isForbidden());

        String owner = loginToken(seedUser("own7@c.com", Role.OWNER));
        mockMvc.perform(put("/api/availability/policy").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"maxAdvanceDays":30,"minBookingLeadTimeHours":1,
                                 "cancellationLeadTimeHours":6}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxAdvanceDays").value(30));
        mockMvc.perform(get("/api/availability/policy").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellationLeadTimeHours").value(6));
    }

    // ===== Horario público =====

    @Test
    void publicoSinAutenticacionYNoFiltraPrivados() throws Exception {
        Clinic clinic = seedClinic("UTC");
        User u = seedUser("den10@c.com", Role.DENTIST);
        Dentist d = seedDentist(u, "Hilda", "Diez");
        String owner = loginToken(seedUser("own8@c.com", Role.OWNER));

        mockMvc.perform(put("/api/schedules/clinic").header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weekBody(day -> openDay(day, "09:00:00", "17:00:00"))))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/schedules/dentist/" + d.getId()).header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(weekBody(day -> openDay(day, "09:00:00", "12:00:00"))))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/schedules/clinic/exceptions/" + DATE)
                        .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"closed":true,"reason":"Motivo privado"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/schedules/dentist/" + d.getId() + "/time-off")
                        .header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startDate":"2026-10-13","endDate":"2026-10-13",
                                 "startTime":"10:00:00","endTime":"11:00:00","reason":"Privado"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/public/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("UTC"))
                .andExpect(jsonPath("$.days", hasSize(7)))
                .andExpect(jsonPath("$.exceptions", hasSize(1)))
                .andExpect(jsonPath("$.dentists", hasSize(1)))
                .andExpect(content().string(not(containsString("Motivo privado"))))
                .andExpect(content().string(not(containsString("Privado"))))
                .andExpect(content().string(not(containsString("\"breaks\""))))
                .andExpect(content().string(not(containsString("timeOff"))))
                .andExpect(content().string(not(containsString("reason"))));
    }

    // ===== Superficie pública: mínimo privilegio =====

    @Test
    void elNamespacePublicoNoEsUnBypassGenericoDeAutorizacion() throws Exception {
        seedClinic("UTC");

        // GET de la ruta aprobada, sin sesión: permitido.
        mockMvc.perform(get("/api/public/schedule"))
                .andExpect(status().isOk());

        // Otros métodos sobre la ruta existente: 405, no 200 ni bypass.
        mockMvc.perform(post("/api/public/schedule")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put("/api/public/schedule")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());

        // Ruta ficticia bajo el namespace /api/public: no debe demostrar bypass.
        // Anónimo: la cadena exige autenticación (401), no la permite.
        mockMvc.perform(get("/api/public/no-existe"))
                .andExpect(status().isUnauthorized());

        // Autenticado: fuera de la matriz y fuera de la allowlist → fallo cerrado (403).
        String token = loginToken(seedUser("ownpub@c.com", Role.OWNER));
        mockMvc.perform(get("/api/public/no-existe").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());

        // Un usuario autenticado puede consultar la ruta pública desde el área privada.
        mockMvc.perform(get("/api/public/schedule").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    // ===== Helpers =====

    private static String weekBody(IntFunction<String> day) {
        StringBuilder sb = new StringBuilder("{\"days\":[");
        for (int d = 1; d <= 7; d++) {
            if (d > 1) {
                sb.append(',');
            }
            sb.append(day.apply(d));
        }
        return sb.append("]}").toString();
    }

    private static String openDay(int day, String start, String end) {
        return "{\"dayOfWeek\":" + day + ",\"open\":true,\"startTime\":\"" + start
                + "\",\"endTime\":\"" + end + "\",\"breaks\":[]}";
    }

    private static String open(int day, String start, String end, String bStart, String bEnd) {
        String breaks = bStart == null ? "[]"
                : "[{\"startTime\":\"" + bStart + "\",\"endTime\":\"" + bEnd + "\"}]";
        return "{\"dayOfWeek\":" + day + ",\"open\":true,\"startTime\":\"" + start
                + "\",\"endTime\":\"" + end + "\",\"breaks\":" + breaks + "}";
    }

    private static String closed(int day) {
        return "{\"dayOfWeek\":" + day + ",\"open\":false,\"breaks\":[]}";
    }

    private static String weekAllClosed() {
        return weekBody(SchedulingApiIntegrationTest::closed);
    }

    private static String dentistWeekOpen() {
        return weekBody(d -> open(d, "09:00:00", "12:00:00", null, null));
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

    private Clinic seedClinic(String timezone) {
        return clinicRepository.saveAndFlush(Clinic.create("Clínica", timezone, null, null, null, null,
                null, null, null, null));
    }

    private Dentist seedDentist(User user, String firstName, String lastName) {
        return dentistRepository.saveAndFlush(Dentist.create(user.getId(), firstName, lastName, "600000000",
                null, null, null, null));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
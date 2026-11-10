package com.dentalclinic.appointment.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.dentalclinic.appointment.domain.Notification;
import com.dentalclinic.appointment.domain.NotificationPriority;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.domain.User;

/**
 * Buzón in-app (T2, RF-NT-01..04, PRD §16). Verifica listado, contador de no leídas y marcado como
 * leída, con el aislamiento por usuario como invariante central: el buzón solo muestra y solo deja
 * marcar lo propio, y un recurso ajeno se resuelve como 404 (no 403), sin revelar su existencia.
 */
class NotificationInboxApiTest extends AppointmentIntegrationTestSupport {

    private static final String BASE_READ_AT = "2026-10-06T08:00:00Z";

    // --------------------------------------------------------------- helpers

    private Notification seed(User mailbox, String title) {
        return seed(mailbox, title, false);
    }

    private Notification seed(User mailbox, String title, boolean read) {
        Notification notification = Notification.create(mailbox.getId(), null, title,
                "Cuerpo de " + title, NotificationPriority.NORMAL);
        if (read) {
            notification.markRead(TestTime.now());
        }
        return notificationRepository.saveAndFlush(notification);
    }

    // --------------------------------------------------------------- listado

    @Test
    void listadoSoloDevuelveLasNotificacionesPropias() throws Exception {
        seed(owner, "Para el owner");
        seed(owner, "Otra del owner");
        seed(dentistUser, "Del dentista");

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].userId").doesNotExist())
                .andExpect(jsonPath("$.content[?(@.title == 'Del dentista')]").isEmpty());
    }

    @Test
    void listadoOrdenaPorMasRecientesPrimero() throws Exception {
        seed(owner, "Primera");
        seed(owner, "Segunda");
        seed(owner, "Tercera");

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Tercera"))
                .andExpect(jsonPath("$.content[1].title").value("Segunda"))
                .andExpect(jsonPath("$.content[2].title").value("Primera"));
    }

    @Test
    void buzonVacioDevuelvePaginaVaciaYContadorCero() throws Exception {
        String token = bearer(loginToken(owner));

        mockMvc.perform(get("/api/notifications").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());

        mockMvc.perform(get("/api/notifications/unread-count").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    void paginacionAcotadaARespetarPageYSize() throws Exception {
        for (int i = 0; i < 5; i++) {
            seed(owner, "Aviso " + i);
        }
        String token = bearer(loginToken(owner));

        mockMvc.perform(get("/api/notifications?page=0&size=2").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));

        // Un size disparatado se recorta a 100 (límite del endpoint).
        mockMvc.perform(get("/api/notifications?page=0&size=100000").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.content.length()").value(5));
    }

    // --------------------------------------------------------------- contador

    @Test
    void contadorReflejaSoloLasNoLeidas() throws Exception {
        seed(owner, "No leída 1");
        seed(owner, "No leída 2");
        seed(owner, "Ya leída", true);

        mockMvc.perform(get("/api/notifications/unread-count")
                        .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(2));
    }

    // ---------------------------------------------------------- marcar leída

    @Test
    void marcarComoLeidaFijaReadAtYDescuentaElContador() throws Exception {
        Notification notification = seed(owner, "Pendiente");
        String token = bearer(loginToken(owner));

        mockMvc.perform(post("/api/notifications/" + notification.getId() + "/read")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(notification.getId()))
                .andExpect(jsonPath("$.read").value(true))
                .andExpect(jsonPath("$.readAt").value(BASE_READ_AT));

        mockMvc.perform(get("/api/notifications/unread-count").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unread").value(0));
    }

    @Test
    void marcarDosVecesEsIdempotenteYNoReescribeReadAt() throws Exception {
        Notification notification = seed(owner, "Pendiente");
        String token = bearer(loginToken(owner));

        mockMvc.perform(post("/api/notifications/" + notification.getId() + "/read")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readAt").value(BASE_READ_AT));

        // Avance corto, dentro de la vida del access token (15 min): el segundo marcado llega
        // autenticado y el reloj ya es distinto, de modo que probaríamos una reescritura si existiera.
        TestTime.advance(Duration.ofMinutes(5));

        mockMvc.perform(post("/api/notifications/" + notification.getId() + "/read")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true))
                .andExpect(jsonPath("$.readAt").value(BASE_READ_AT));
    }

    // ------------------------------------------------------------- aislamiento

    @Test
    void noPuedoMarcarLaNotificacionDeOtroUsuario() throws Exception {
        Notification delDentista = seed(dentistUser, "Del dentista");

        mockMvc.perform(post("/api/notifications/" + delDentista.getId() + "/read")
                        .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isNotFound());

        Notification sinCambios = notificationRepository.findById(delDentista.getId()).orElseThrow();
        assertThat(sinCambios.isRead()).isFalse();
    }

    @Test
    void marcarNotificacionInexistenteDevuelve404() throws Exception {
        mockMvc.perform(post("/api/notifications/999999/read")
                        .header("Authorization", bearer(loginToken(owner))))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------ seguridad

    @Test
    void sinAutenticacionDevuelve401EnTodasLasOperaciones() throws Exception {
        mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/notifications/unread-count"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/notifications/1/read")).andExpect(status().isUnauthorized());
    }

    @Test
    void todosLosRolesAccedenSoloASuPropioBuzon() throws Exception {
        List<User> usuarios = List.of(owner, receptionist, dentistUser, patientUser);
        for (User usuario : usuarios) {
            seed(usuario, "Aviso para " + usuario.getRole());
        }

        for (User usuario : usuarios) {
            mockMvc.perform(get("/api/notifications")
                            .header("Authorization", bearer(loginToken(usuario))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].title").value("Aviso para " + usuario.getRole()));
        }
    }
}

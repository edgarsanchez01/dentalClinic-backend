package com.dentalclinic.appointment.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dentalclinic.appointment.api.dto.NotificationResponse;
import com.dentalclinic.appointment.api.dto.UnreadCountResponse;
import com.dentalclinic.appointment.application.NotificationInboxService;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.identity.infrastructure.security.CurrentUser;
import com.dentalclinic.patient.api.dto.PageResponse;

/**
 * Buzón de notificaciones in-app (RF-NT-01..04, PRD §16, Architecture §7.4/§9.5).
 *
 * <p>Ruta bajo el grupo {@code /api/notifications/**}: los cuatro roles acceden
 * <strong>solo a lo suyo</strong> ({@code SELF_READ_WRITE}). El destinatario es siempre el usuario
 * autenticado ({@link CurrentUser}); el cliente <strong>nunca</strong> envía un {@code userId}.
 *
 * <p>Estos endpoints solo <strong>consultan y marcan como leídas</strong>. La generación de
 * notificaciones no cambia: sigue ocurriendo en la transacción de negocio de la cita
 * ({@code AppointmentCommunicationPlanner}).
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationInboxService inbox;

    public NotificationController(NotificationInboxService inbox) {
        this.inbox = inbox;
    }

    /**
     * Listado paginado del buzón propio, más recientes primero. La paginación está acotada a 100
     * filas por página (RF-NT-04, PRD §16.3).
     */
    @GetMapping
    public PageResponse<NotificationResponse> list(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        AuthenticatedUser actor = CurrentUser.require();
        Page<NotificationResponse> result =
                inbox.list(actor.userId(), pageable).map(NotificationResponse::from);
        return PageResponse.from(result);
    }

    /** Contador de no leídas del usuario actual (RF-NT-03). */
    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount() {
        return new UnreadCountResponse(inbox.unreadCount(CurrentUser.require().userId()));
    }

    /**
     * Marca como leída una notificación propia (RF-NT-04). Idempotente: repetir la llamada sobre una
     * ya leída devuelve 200 sin alterar {@code readAt}. Una notificación de otro usuario responde 404.
     */
    @PostMapping("/{id}/read")
    public NotificationResponse markRead(@PathVariable("id") Long id) {
        return NotificationResponse.from(inbox.markRead(CurrentUser.require().userId(), id));
    }
}

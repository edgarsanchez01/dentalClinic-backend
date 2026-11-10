package com.dentalclinic.appointment.application;

import java.time.Clock;
import java.time.Instant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.appointment.domain.Notification;
import com.dentalclinic.appointment.infrastructure.repository.NotificationRepository;
import com.dentalclinic.identity.domain.exception.NotFoundException;

/**
 * Buzón in-app: lectura de las notificaciones propias y marcado como leídas (RF-NT-03/04, PRD §16).
 *
 * <p>Es la cara de <strong>consulta</strong> del buzón. La <strong>generación</strong> no vive aquí:
 * la sigue haciendo {@link AppointmentCommunicationPlanner} en la transacción de negocio, insertando
 * directamente en {@code Notification} sin outbox ni dispatcher (PRD §16.4, Architecture §9.5). Este
 * servicio nunca crea notificaciones.
 *
 * <p><strong>Aislamiento:</strong> toda operación filtra por el {@code userId} del usuario
 * autenticado. Un recurso de otro usuario se resuelve como {@link NotFoundException} (404), igual que
 * "no existe" (Architecture §8.2), de modo que el buzón no revela la existencia de notificaciones de
 * terceros.
 */
@Service
public class NotificationInboxService {

    private final NotificationRepository notificationRepository;
    private final Clock clock;

    public NotificationInboxService(NotificationRepository notificationRepository, Clock clock) {
        this.notificationRepository = notificationRepository;
        this.clock = clock;
    }

    /** Página del buzón del usuario. El orden lo decide el llamador (más recientes primero). */
    @Transactional(readOnly = true)
    public Page<Notification> list(Long userId, Pageable pageable) {
        return notificationRepository.findByUserId(userId, pageable);
    }

    /** Número de notificaciones no leídas del usuario (RF-NT-03). */
    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return notificationRepository.countByUserIdAndReadAtIsNull(userId);
    }

    /**
     * Marca como leída una notificación <strong>del propio usuario</strong> (RF-NT-04).
     *
     * <p>Idempotente: si ya estaba leída, devuelve la fila sin cambios. Una notificación de otro
     * usuario (o inexistente) produce 404.
     */
    @Transactional
    public Notification markRead(Long userId, Long notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .filter(row -> row.belongsTo(userId))
                .orElseThrow(NotFoundException::new);
        notification.markRead(Instant.now(clock));
        return notificationRepository.save(notification);
    }
}

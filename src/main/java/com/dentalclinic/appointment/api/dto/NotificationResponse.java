package com.dentalclinic.appointment.api.dto;

import java.time.Instant;

import com.dentalclinic.appointment.domain.Notification;
import com.dentalclinic.appointment.domain.NotificationPriority;

/**
 * Representación de una notificación in-app para su buzón (PRD §16.2). Es un DTO: la entidad JPA no
 * cruza la frontera HTTP (Agents.md Regla 5).
 *
 * <p>{@code read} se deriva de {@code readAt} para reflejar el modelo del PRD
 * ({@code read}/{@code readAt}); la columna persistida es {@code read_at}.
 */
public record NotificationResponse(
        Long id,
        String title,
        String body,
        NotificationPriority priority,
        Long appointmentId,
        String relatedType,
        Long relatedId,
        boolean read,
        Instant readAt,
        Instant createdAt) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getTitle(),
                notification.getBody(),
                notification.getPriority(),
                notification.getAppointmentId(),
                notification.getRelatedType(),
                notification.getRelatedId(),
                notification.isRead(),
                notification.getReadAt(),
                notification.getCreatedAt());
    }
}

package com.dentalclinic.appointment.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Notificación in-app persistente (Architecture.md §9.2/15.2, PRD.md §14.2).
 *
 * <p>Se inserta DIRECTAMENTE en la transacción de negocio, sin outbox ni dispatcher. Sin estados de
 * entrega y sin clave idempotente: la deduplicación in-app recae en la intención
 * ({@code communication_intention}).
 *
 * <p>{@code userId} es el buzón (la cuenta); un paciente sin cuenta no puede recibir in-app, y su
 * ausencia se registra en la intención. {@code appointmentId} es el "recurso" que habilita la FK
 * compuesta de la intención; los avisos no-cita (futuras fases) usarían {@code relatedType}/{@code
 * relatedId} y dejarían {@code appointmentId} nulo.
 */
@Entity
@Table(name = "notification")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "appointment_id")
    private Long appointmentId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 10)
    private NotificationPriority priority;

    @Column(name = "related_type", length = 40)
    private String relatedType;

    @Column(name = "related_id")
    private Long relatedId;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT now()")
    private Instant createdAt;

    protected Notification() {
        // Para JPA.
    }

    private Notification(Long userId, Long appointmentId, String title, String body,
                         NotificationPriority priority) {
        this.userId = Objects.requireNonNull(userId, "userId es obligatorio");
        this.appointmentId = appointmentId;
        this.title = Objects.requireNonNull(title, "title es obligatorio");
        this.body = Objects.requireNonNull(body, "body es obligatorio");
        this.priority = priority;
    }

    public static Notification create(Long userId, Long appointmentId, String title, String body,
                                      NotificationPriority priority) {
        return new Notification(userId, appointmentId, title, body, priority);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getAppointmentId() {
        return appointmentId;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public NotificationPriority getPriority() {
        return priority;
    }

    public String getRelatedType() {
        return relatedType;
    }

    public Long getRelatedId() {
        return relatedId;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
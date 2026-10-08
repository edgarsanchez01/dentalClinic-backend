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
 * Intención de comunicar por destinatario, en la transacción de negocio (PRD.md §14.6, D-F6-16/18).
 *
 * <p>Por cada fila de la matriz §14.6 se registra quién iba a ser comunicado, por qué canal y con
 * qué resultado de resolución de identidad (A-03/D-F6-12). Es la fuente del "avatar" de ausencias
 * ({@code NO_CONTACT_CHANNEL}) y permite el dedupe por identidad de las notificaciones in-app.
 *
 * <p><strong>Frontera F6↔F8:</strong> solo los tipos con comunicación ({@code CREATED},
 * {@code RESCHEDULED}, {@code CANCELLED}, {@code REMINDER}) generan intenciones; los avisos
 * in-app de completado/no-show/llegada/comienzo se insertan directo en {@code Notification} sin
 * intención. Lo garantiza también el CHECK {@code ci_event_check} de V006.
 *
 * <p>Invariantes (A-06) que el motor verifica: enlaces mutuamente excluyentes y por canal,
 * dirección solo en EMAIL, {@code DELIVERABLE} ⇒ fila enlazada real y coherente,
 * {@code NO_CONTACT_CHANNEL} ⇒ cero enlaces, y una intención por (cita, evento, versión, canal,
 * rol) ({@code ci_uniq}).
 */
@Entity
@Table(name = "communication_intention")
public class CommunicationIntention {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "appointment_id", nullable = false)
    private Long appointmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private CommunicationType eventType;

    @Column(name = "occurrence_version", nullable = false)
    private int occurrenceVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "required_role", nullable = false, length = 20)
    private RequiredRole requiredRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "party_kind", nullable = false, length = 10)
    private PartyKind partyKind;

    @Column(name = "party_id", nullable = false)
    private Long partyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_kind", nullable = false, length = 10)
    private RecipientKind recipientKind;

    @Column(name = "recipient_user_id")
    private Long recipientUserId;

    @Column(name = "address", length = 320)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution", nullable = false, length = 24)
    private Resolution resolution;

    @Column(name = "communication_id")
    private Long communicationId;

    @Column(name = "notification_id")
    private Long notificationId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT now()")
    private Instant createdAt;

    protected CommunicationIntention() {
        // Para JPA.
    }

    private CommunicationIntention(Long appointmentId, CommunicationType eventType,
                                    int occurrenceVersion, Channel channel,
                                    RequiredRole requiredRole, PartyKind partyKind, Long partyId,
                                    RecipientKind recipientKind, Long recipientUserId,
                                    String address, Resolution resolution, Long communicationId,
                                    Long notificationId) {
        this.appointmentId = Objects.requireNonNull(appointmentId, "appointmentId es obligatorio");
        this.eventType = Objects.requireNonNull(eventType, "eventType es obligatorio");
        this.occurrenceVersion = occurrenceVersion;
        this.channel = Objects.requireNonNull(channel, "channel es obligatorio");
        this.requiredRole = Objects.requireNonNull(requiredRole, "requiredRole es obligatorio");
        this.partyKind = Objects.requireNonNull(partyKind, "partyKind es obligatorio");
        this.partyId = Objects.requireNonNull(partyId, "partyId es obligatorio");
        this.recipientKind = Objects.requireNonNull(recipientKind, "recipientKind es obligatorio");
        this.recipientUserId = recipientUserId;
        this.address = address;
        this.resolution = Objects.requireNonNull(resolution, "resolution es obligatorio");
        this.communicationId = communicationId;
        this.notificationId = notificationId;
    }

    /** EMAIL con destinatario/address reales, enlazada a su fila de outbox. */
    public static CommunicationIntention deliverableEmail(Long appointmentId,
            CommunicationType eventType, int occurrenceVersion, RequiredRole requiredRole,
            PartyKind partyKind, Long partyId, RecipientKind recipientKind, Long recipientUserId,
            String address, Long communicationId) {
        return new CommunicationIntention(appointmentId, eventType, occurrenceVersion,
                Channel.EMAIL, requiredRole, partyKind, partyId, recipientKind, recipientUserId,
                address, Resolution.DELIVERABLE, communicationId, null);
    }

    /** IN_APP con cuenta real, enlazada a su notificación. */
    public static CommunicationIntention deliverableInApp(Long appointmentId,
            CommunicationType eventType, int occurrenceVersion, RequiredRole requiredRole,
            PartyKind partyKind, Long partyId, Long recipientUserId, Long notificationId) {
        return new CommunicationIntention(appointmentId, eventType, occurrenceVersion,
                Channel.IN_APP, requiredRole, partyKind, partyId, RecipientKind.USER,
                recipientUserId, null, Resolution.DELIVERABLE, null, notificationId);
    }

    /** Sin canal real (sin cuenta/sin correo): no bloquea la cita (D-F6-01). */
    public static CommunicationIntention noContact(Long appointmentId, CommunicationType eventType,
            int occurrenceVersion, Channel channel, RequiredRole requiredRole, PartyKind partyKind,
            Long partyId) {
        RecipientKind fallback = requiredRole == RequiredRole.PATIENT
                ? RecipientKind.PATIENT
                : RecipientKind.USER;
        return new CommunicationIntention(appointmentId, eventType, occurrenceVersion, channel,
                requiredRole, partyKind, partyId, fallback, null, null,
                Resolution.NO_CONTACT_CHANNEL, null, null);
    }

    public Channel channel() {
        return channel;
    }

    public Long getId() {
        return id;
    }

    public Long getAppointmentId() {
        return appointmentId;
    }

    public CommunicationType getEventType() {
        return eventType;
    }

    public int getOccurrenceVersion() {
        return occurrenceVersion;
    }

    public RequiredRole getRequiredRole() {
        return requiredRole;
    }

    public PartyKind getPartyKind() {
        return partyKind;
    }

    public Long getPartyId() {
        return partyId;
    }

    public RecipientKind getRecipientKind() {
        return recipientKind;
    }

    public Long getRecipientUserId() {
        return recipientUserId;
    }

    public String getAddress() {
        return address;
    }

    public Resolution getResolution() {
        return resolution;
    }

    public Long getCommunicationId() {
        return communicationId;
    }

    public Long getNotificationId() {
        return notificationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public enum Channel {
        EMAIL,
        IN_APP
    }

    public enum RequiredRole {
        PATIENT,
        DENTIST,
        CREATOR
    }

    public enum PartyKind {
        PATIENT,
        DENTIST,
        USER
    }

    public enum Resolution {
        DELIVERABLE,
        NO_CONTACT_CHANNEL
    }
}
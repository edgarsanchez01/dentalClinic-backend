package com.dentalclinic.appointment.application;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.appointment.domain.Communication;
import com.dentalclinic.appointment.domain.CommunicationIntention;
import com.dentalclinic.appointment.domain.CommunicationIntention.Channel;
import com.dentalclinic.appointment.domain.CommunicationIntention.PartyKind;
import com.dentalclinic.appointment.domain.CommunicationIntention.RequiredRole;
import com.dentalclinic.appointment.domain.CommunicationStatus;
import com.dentalclinic.appointment.domain.CommunicationType;
import com.dentalclinic.appointment.domain.Notification;
import com.dentalclinic.appointment.domain.NotificationPriority;
import com.dentalclinic.appointment.domain.RecipientKind;
import com.dentalclinic.appointment.infrastructure.repository.CommunicationIntentionRepository;
import com.dentalclinic.appointment.infrastructure.repository.CommunicationRepository;
import com.dentalclinic.appointment.infrastructure.repository.NotificationRepository;
import com.dentalclinic.clinic.domain.Dentist;
import com.dentalclinic.identity.application.UserService;
import com.dentalclinic.patient.domain.Patient;

/**
 * Materialización de la matriz de notificación PRD.md §14.6 en la transacción de negocio.
 *
 * <p>Fase 6 escribe <strong>lo que ya está aprobado</strong>: la fila de outbox del canal EMAIL, la
 * notificación in-app y la intención por (parte, canal) resuelta por identidad (A-03/D-F6-12). Nada
 * se envía: el dispatcher es de F8 y la deduplicación in-app recae en la intención.
 *
 * <p><strong>Qué NO hace este planificador (Frontera F6↔F8):</strong> no abre SMTP, no reintenta,
 * no cambia estados de comunicación, no genera recordatorios ni feedback. Tampoco bloquea la cita
 * por no encontrar canal: se registra {@code NO_CONTACT_CHANNEL} (D-F6-01) y la cita sigue.
 *
 * <p>Los avisos de completado/no-show/llegada/comienzo se insertan directo en {@code Notification}
 * sin fila de intención: {@code communication_intention.event_type} solo admite
 * CREATED/RESCHEDULED/CANCELLED/REMINDER (CHECK {@code ci_event_check}).
 */
@Service
public class AppointmentCommunicationPlanner {

    private static final DateTimeFormatter FECHA_HORA =
            DateTimeFormatter.ofPattern("EEEE d 'de' MMMM', a las' HH:mm", new Locale("es", "ES"));
    private static final NotificationPriority PRIORITY = NotificationPriority.NORMAL;

    private final CommunicationRepository communicationRepository;
    private final NotificationRepository notificationRepository;
    private final CommunicationIntentionRepository intentionRepository;
    private final UserService userService;

    public AppointmentCommunicationPlanner(CommunicationRepository communicationRepository,
            NotificationRepository notificationRepository,
            CommunicationIntentionRepository intentionRepository, UserService userService) {
        this.communicationRepository = communicationRepository;
        this.notificationRepository = notificationRepository;
        this.intentionRepository = intentionRepository;
        this.userService = userService;
    }

    /** CREATED (v1): paciente EMAIL+IN_APP; dentista solo IN_APP (§14.6 fila "Notificación"). */
    public void created(Appointment appointment, Patient patient, Dentist dentist, ZoneId zone) {
        int version = appointment.getOccurrenceVersion();
        String fecha = fechaHora(appointment.getStartAt(), zone);
        String patientNombre = patient.getFirstName() + " " + patient.getLastName();
        participant(appointment, CommunicationType.CREATED, version, RequiredRole.PATIENT,
                PartyKind.PATIENT, patient.getId(), identityOf(patient),
                List.of(Channel.EMAIL, Channel.IN_APP), "Cita programada",
                "Tu cita para el " + fecha + " quedó confirmada.");
        participant(appointment, CommunicationType.CREATED, version, RequiredRole.DENTIST,
                PartyKind.DENTIST, dentist.getId(), identityOf(dentist), List.of(Channel.IN_APP),
                "Nueva cita", "Tienes una cita el " + fecha + " con " + patientNombre + ".");
    }

    /**
     * RESCHEDULED (v{N+1}): anula las RESCHEDULED anteriores (A-07) y comunica a las tres partes
     * por EMAIL+IN_APP. La parte CREATOR solo se materializa si el actor no es ya el paciente o el
     * dentista (evita ping a la misma persona dos veces, D-F6-16).
     */
    public void rescheduled(Appointment appointment, Patient patient, Dentist dentist,
                            Long creatorUserId, ZoneId zone, Instant now) {
        supersedePendingRescheduled(appointment.getId(), appointment.getOccurrenceVersion(), now);
        int version = appointment.getOccurrenceVersion();
        String fecha = fechaHora(appointment.getStartAt(), zone);
        String patientNombre = patient.getFirstName() + " " + patient.getLastName();
        participant(appointment, CommunicationType.RESCHEDULED, version, RequiredRole.PATIENT,
                PartyKind.PATIENT, patient.getId(), identityOf(patient),
                List.of(Channel.EMAIL, Channel.IN_APP), "Cita reprogramada",
                "Tu cita se movió al " + fecha + ".");
        participant(appointment, CommunicationType.RESCHEDULED, version, RequiredRole.DENTIST,
                PartyKind.DENTIST, dentist.getId(), identityOf(dentist),
                List.of(Channel.EMAIL, Channel.IN_APP), "Cita reprogramada",
                "La cita con " + patientNombre + " se movió al " + fecha + ".");
        if (creatorNeedsParty(creatorUserId, patient, dentist)) {
            participant(appointment, CommunicationType.RESCHEDULED, version, RequiredRole.CREATOR,
                    PartyKind.USER, creatorUserId, identityOf(creatorUserId),
                    List.of(Channel.EMAIL, Channel.IN_APP), "Cita reprogramada",
                    "La cita de " + patientNombre + " fue reprogramada por ti al " + fecha + ".");
        }
    }

    /** CANCELLED (versión actual, sin incrementar). Comunicación a las tres partes por EMAIL+IN_APP. */
    public void cancelled(Appointment appointment, Patient patient, Dentist dentist,
                          Long creatorUserId, ZoneId zone) {
        int version = appointment.getOccurrenceVersion();
        String fecha = fechaHora(appointment.getStartAt(), zone);
        String patientNombre = patient.getFirstName() + " " + patient.getLastName();
        participant(appointment, CommunicationType.CANCELLED, version, RequiredRole.PATIENT,
                PartyKind.PATIENT, patient.getId(), identityOf(patient),
                List.of(Channel.EMAIL, Channel.IN_APP), "Cita cancelada",
                "La cita del " + fecha + " fue cancelada. Si tienes dudas, contáctanos.");
        participant(appointment, CommunicationType.CANCELLED, version, RequiredRole.DENTIST,
                PartyKind.DENTIST, dentist.getId(), identityOf(dentist),
                List.of(Channel.EMAIL, Channel.IN_APP), "Cita cancelada",
                "Se canceló la cita del " + fecha + " con " + patientNombre + ".");
        if (creatorNeedsParty(creatorUserId, patient, dentist)) {
            participant(appointment, CommunicationType.CANCELLED, version, RequiredRole.CREATOR,
                    PartyKind.USER, creatorUserId, identityOf(creatorUserId),
                    List.of(Channel.EMAIL, Channel.IN_APP), "Cita cancelada",
                    "Cancelaste la cita de " + patientNombre + " del " + fecha + ".");
        }
    }

    /** Aviso in-app al paciente sin fila de intención (no hay comunicación ni intención para estos). */
    public void notifyPatientInApp(Appointment appointment, Patient patient, String title, String body) {
        if (patient.getUserId() == null) {
            return;
        }
        notificationRepository.save(Notification.create(patient.getUserId(), appointment.getId(),
                title, body, PRIORITY));
    }

    /** Aviso in-app al profesional asignado sin fila de intención. */
    public void notifyDentistInApp(Appointment appointment, Dentist dentist, String title, String body) {
        notificationRepository.save(Notification.create(dentist.getUserId(), appointment.getId(),
                title, body, PRIORITY));
    }

    private void supersedePendingRescheduled(Long appointmentId, int currentVersion, Instant now) {
        for (Communication c : communicationRepository.findSupersedable(appointmentId,
                CommunicationType.RESCHEDULED, CommunicationStatus.PENDING, currentVersion)) {
            c.markSuperseded(now);
        }
    }

    private void participant(Appointment appointment, CommunicationType type, int version,
            RequiredRole role, PartyKind partyKind, Long partyId, ParticipantIdentity identity,
            List<Channel> channels, String inAppTitle, String inAppBody) {
        for (Channel channel : channels) {
            materialize(appointment, type, version, role, partyKind, partyId, identity, channel,
                    inAppTitle, inAppBody);
        }
    }

    private void materialize(Appointment appointment, CommunicationType type, int version,
            RequiredRole role, PartyKind partyKind, Long partyId, ParticipantIdentity identity,
            Channel channel, String inAppTitle, String inAppBody) {
        if (channel == Channel.EMAIL) {
            String address = identity.userEmail() != null ? identity.userEmail() : identity.patientEmail();
            if (address == null) {
                noContact(appointment, type, version, channel, role, partyKind, partyId);
                return;
            }
            RecipientKind kind = identity.userEmail() != null ? RecipientKind.USER : RecipientKind.PATIENT;
            Long recipientPatientId = kind == RecipientKind.PATIENT ? identity.patientId() : null;
            Long recipientUserId = kind == RecipientKind.USER ? identity.userId() : null;
            Long identityId = kind == RecipientKind.USER ? identity.userId() : identity.patientId();
            Communication communication = communicationRepository.save(Communication.create(
                    appointment.getId(), type, kind, recipientPatientId, recipientUserId, address,
                    emailKey(appointment.getId(), type, version, kind, identityId), version));
            intentionRepository.save(CommunicationIntention.deliverableEmail(
                    appointment.getId(), type, version, role, partyKind, partyId, kind,
                    recipientUserId, address, communication.getId()));
            return;
        }
        if (identity.userId() == null || identity.userEmail() == null) {
            noContact(appointment, type, version, channel, role, partyKind, partyId);
            return;
        }
        Notification notification = notificationRepository.save(Notification.create(
                identity.userId(), appointment.getId(), inAppTitle, inAppBody, PRIORITY));
        intentionRepository.save(CommunicationIntention.deliverableInApp(
                appointment.getId(), type, version, role, partyKind, partyId, identity.userId(),
                notification.getId()));
    }

    private void noContact(Appointment appointment, CommunicationType type, int version,
            Channel channel, RequiredRole role, PartyKind partyKind, Long partyId) {
        intentionRepository.save(CommunicationIntention.noContact(appointment.getId(), type, version,
                channel, role, partyKind, partyId));
    }

    private static boolean creatorNeedsParty(Long creatorUserId, Patient patient, Dentist dentist) {
        if (patient.getUserId() != null && patient.getUserId().equals(creatorUserId)) {
            return false;
        }
        return !dentist.getUserId().equals(creatorUserId);
    }

    private ParticipantIdentity identityOf(Patient patient) {
        if (patient.getUserId() == null) {
            return new ParticipantIdentity(null, null, patient.getId(), patient.getEmail());
        }
        String email = userService.findById(patient.getUserId())
                .map(user -> user.getEmail()).orElse(null);
        return new ParticipantIdentity(patient.getUserId(), email, patient.getId(), patient.getEmail());
    }

    private ParticipantIdentity identityOf(Dentist dentist) {
        String email = userService.findById(dentist.getUserId())
                .map(user -> user.getEmail()).orElse(null);
        return new ParticipantIdentity(dentist.getUserId(), email, null, null);
    }

    private ParticipantIdentity identityOf(Long userId) {
        String email = userService.findById(userId).map(user -> user.getEmail()).orElse(null);
        return new ParticipantIdentity(userId, email, null, null);
    }

    /**
     * Gramática A-04/D-F6-13: {@code APPOINTMENT:{id}:{EVENT}:EMAIL:{patientId}|U{users.id}},
     * con {@code :V{occurrenceVersion}} para RESCHEDULED y REMINDER. Dedupe por IDENTIDAD.
     */
    private static String emailKey(Long appointmentId, CommunicationType type, int version,
                                   RecipientKind kind, Long identityId) {
        String identity = kind == RecipientKind.USER ? "U" + identityId : String.valueOf(identityId);
        String versionSuffix = type == CommunicationType.RESCHEDULED
                || type == CommunicationType.REMINDER ? ":V" + version : "";
        return "APPOINTMENT:" + appointmentId + ":" + type + ":EMAIL:" + identity + versionSuffix;
    }

    private static String fechaHora(Instant instant, ZoneId zone) {
        return FECHA_HORA.format(instant.atZone(zone));
    }

    /** Identidad resuelta de una parte (A-03): el usuario real si existe, si no el paciente. */
    private record ParticipantIdentity(Long userId, String userEmail, Long patientId,
                                       String patientEmail) {
    }
}
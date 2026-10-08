package com.dentalclinic.appointment.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.appointment.api.dto.AppointmentEventView;
import com.dentalclinic.appointment.api.dto.AppointmentResponse;
import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.appointment.domain.AppointmentEvent;
import com.dentalclinic.appointment.domain.AppointmentEventType;
import com.dentalclinic.appointment.domain.AppointmentStatus;
import com.dentalclinic.appointment.domain.HttpIdempotency;
import com.dentalclinic.appointment.infrastructure.repository.AppointmentEventRepository;
import com.dentalclinic.appointment.infrastructure.repository.AppointmentRepository;
import com.dentalclinic.appointment.infrastructure.repository.HttpIdempotencyRepository;
import com.dentalclinic.clinic.application.ClinicService;
import com.dentalclinic.clinic.application.DentistService;
import com.dentalclinic.clinic.domain.Clinic;
import com.dentalclinic.clinic.domain.Dentist;
import com.dentalclinic.identity.application.UserService;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.patient.application.PatientService;
import com.dentalclinic.patient.domain.Patient;
import com.dentalclinic.scheduling.application.AvailabilityService;
import com.dentalclinic.scheduling.domain.ScheduleConflictException;
import com.dentalclinic.scheduling.domain.exception.InvalidScheduleException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Casos de uso de citas (Fase 6). Orquesta las reglas de negocio aprobadas (A-01..A-20, D-F6-10),
 * la autorización por propiedad (§7.2/§7.5, §8.2) y la materialización de comunicaciones/avisos
 * (§14.6) delegada en {@link AppointmentCommunicationPlanner}.
 *
 * <p>La disponibilidad y sus invariantes geométricas viven en el módulo de scheduling
 * ({@link AvailabilityService}: ventanas, breaks, franquicias, lead time, anti-doble-booking).
 * Aquí se decide el <em>quién</em> (rol + propiedad) y el <em>cuándo</em> (política de la clínica).
 *
 * <p><strong>Idempotencia A-16:</strong> la creación exige {@code Idempotency-Key} y la consume de
 * forma atómica con la operación (la fila {@code http_idempotency} se inserta en esta misma
 * transacción). El coordinador {@link AppointmentCreateCoordinator} gestiona replay y conflictos.
 */
@Service
public class AppointmentService {

    static final String CREATE_OPERATION = "CREATE_APPOINTMENT";

    private static final int MAX_PAGE_SIZE = 100;
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter
            .ofPattern("EEEE d 'de' MMMM', a las' HH:mm", new Locale("es", "ES"));

    private final AppointmentRepository appointmentRepository;
    private final AppointmentEventRepository appointmentEventRepository;
    private final HttpIdempotencyRepository httpIdempotencyRepository;
    private final PatientService patientService;
    private final DentistService dentistService;
    private final ClinicService clinicService;
    private final AvailabilityService availabilityService;
    private final AppointmentCommunicationPlanner communications;
    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AppointmentService(AppointmentRepository appointmentRepository,
            AppointmentEventRepository appointmentEventRepository,
            HttpIdempotencyRepository httpIdempotencyRepository, PatientService patientService,
            DentistService dentistService, ClinicService clinicService,
            AvailabilityService availabilityService,
            AppointmentCommunicationPlanner communications, UserService userService,
            ObjectMapper objectMapper, Clock clock) {
        this.appointmentRepository = appointmentRepository;
        this.appointmentEventRepository = appointmentEventRepository;
        this.httpIdempotencyRepository = httpIdempotencyRepository;
        this.patientService = patientService;
        this.dentistService = dentistService;
        this.clinicService = clinicService;
        this.availabilityService = availabilityService;
        this.communications = communications;
        this.userService = userService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Crea la cita Y consume la {@code Idempotency-Key} en la misma transacción (A-16). Si algo
     * revierte, la clave no queda consumida.
     */
    @Transactional
    public CreatedAppointment create(AppointmentData data, AuthenticatedUser actor,
                                     String idempotencyKey, String requestHash) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey es obligatorio");
        Instant now = clock.instant();
        Clinic clinic = clinicService.get();
        ZoneId zone = ZoneId.of(clinic.getTimezone());

        assertCanCreateFor(actor, data);
        Patient patient = patientService.get(data.patientId());
        if (!patient.isActive()) {
            throw new ScheduleConflictException("El paciente está dado de baja; no se pueden agendar citas.");
        }
        Dentist dentist = dentistService.get(data.dentistId());
        requireActiveProfessional(dentist);

        validateRange(data.startAt(), data.endAt());
        validateBookingWindow(now, clinic, zone, data.startAt(), actor.role());

        availabilityService.assertBookable(data.dentistId(), data.startAt(), data.endAt(), null);

        Appointment appointment = appointmentRepository.save(Appointment.create(
                data.patientId(), data.dentistId(), actor.userId(), data.startAt(), data.endAt(),
                durationMinutes(data.startAt(), data.endAt()), data.reason(), data.notes()));

        AppointmentEvent event = AppointmentEvent.create(appointment.getId(),
                AppointmentEventType.APPOINTMENT_CREATED, actor.userId(),
                appointment.getOccurrenceVersion(), null);
        appointmentEventRepository.save(event);

        AppointmentResponse response = toResponse(appointment, patient, dentist);
        Map<String, Object> body = objectMapper.convertValue(response, new TypeReference<>() {
        });
        httpIdempotencyRepository.save(HttpIdempotency.create(actor.userId(), CREATE_OPERATION,
                idempotencyKey, requestHash, HttpStatus.CREATED.value(), body));

        communications.created(appointment, patient, dentist, zone);
        return new CreatedAppointment(response);
    }

    @Transactional(readOnly = true)
    public Appointment get(Long id, AuthenticatedUser actor) {
        Appointment appointment = appointmentRepository.findById(id)
                .orElseThrow(NotFoundException::new);
        requireVisibleTo(appointment, actor);
        return appointment;
    }

    /** Auditoría append-only de una cita (RF-AP-05/15), con la misma regla de visibilidad. */
    @Transactional(readOnly = true)
    public List<AppointmentEvent> events(Long id, AuthenticatedUser actor) {
        Appointment appointment = appointmentRepository.findById(id)
                .orElseThrow(NotFoundException::new);
        requireVisibleTo(appointment, actor);
        return appointmentEventRepository.findByAppointmentIdOrderByCreatedAtAscIdAsc(id);
    }

    /**
     * Agenda (§17.5) con filtros opcionales. Para un PATIENT el filtro de paciente se fuerza a su
     * propio {@code patientId}: nunca ve citas ajenas (matriz §7.4, {@code SELF_READ}).
     */
    /**
     * Agenda (§17.5) con filtros opcionales. Para un PATIENT el filtro de paciente se fuerza a su
     * propio {@code patientId}: nunca ve citas ajenas (matriz §7.4, {@code SELF_READ}).
     *
     * <p>Los filtros se componen con una {@code Specification} y no con parámetros anulables en
     * JPQL: {@code :param IS NULL} deja parámetros sin tipo que PostgreSQL rechaza (42P18) para
     * los instantes, y un listado que falla no es un listado.
     */
    @Transactional(readOnly = true)
    public Page<Appointment> list(Long dentistId, Long patientId, AppointmentStatus status,
                                  Instant from, Instant to, AuthenticatedUser actor,
                                  Pageable pageable) {
        if (actor.role() == Role.PATIENT) {
            Long own = patientService.findPatientIdForUser(actor.userId()).orElse(null);
            if (own == null) {
                return Page.empty(pageable);
            }
            patientId = own;
        }
        return appointmentRepository.findAll(filtro(dentistId, patientId, status, from, to),
                pageable);
    }

    private static Specification<Appointment> filtro(Long dentistId, Long patientId,
            AppointmentStatus status, Instant from, Instant to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (patientId != null) {
                predicates.add(cb.equal(root.get("patientId"), patientId));
            }
            if (dentistId != null) {
                predicates.add(cb.equal(root.get("dentistId"), dentistId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("startAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("startAt"), to));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    @Transactional
    public AppointmentResponse reschedule(Long id, AuthenticatedUser actor, RescheduleData data) {
        Appointment appointment = appointmentRepository.findById(id)
                .orElseThrow(NotFoundException::new);
        requireScheduled(appointment);
        assertCanControl(appointment, actor);
        if (appointment.getOccurrenceVersion() != data.expectedOccurrenceVersion()) {
            throw new ScheduleConflictException(
                    "La cita fue modificada por otra persona; recarga la vista para ver la versión actual.");
        }

        Instant now = clock.instant();
        Clinic clinic = clinicService.get();
        ZoneId zone = ZoneId.of(clinic.getTimezone());
        validateRange(data.startAt(), data.endAt());
        validateBookingWindow(now, clinic, zone, data.startAt(), actor.role());
        availabilityService.assertBookable(appointment.getDentistId(), data.startAt(),
                data.endAt(), appointment.getId());

        Patient patient = patientService.get(appointment.getPatientId());
        Dentist dentist = dentistService.get(appointment.getDentistId());
        appointment.reschedule(data.startAt(), data.endAt(),
                durationMinutes(data.startAt(), data.endAt()), now);
        appointmentEventRepository.save(AppointmentEvent.create(appointment.getId(),
                AppointmentEventType.APPOINTMENT_RESCHEDULED, actor.userId(),
                appointment.getOccurrenceVersion(), null));
        communications.rescheduled(appointment, patient, dentist, actor.userId(), zone, now);
        return toResponse(appointment, patient, dentist);
    }

    @Transactional
    public AppointmentResponse cancel(Long id, AuthenticatedUser actor) {
        Appointment appointment = appointmentRepository.findById(id)
                .orElseThrow(NotFoundException::new);
        requireScheduled(appointment);
        assertCanControl(appointment, actor);
        Instant now = clock.instant();
        Clinic clinic = clinicService.get();
        if (actor.role() == Role.PATIENT && isBeforeCancellationDeadline(clinic, now,
                appointment.getStartAt())) {
            throw new ScheduleConflictException("Solo puedes cancelar hasta "
                    + clinic.getCancellationLeadTimeHours() + " horas antes de la cita.");
        }

        Patient patient = patientService.get(appointment.getPatientId());
        Dentist dentist = dentistService.get(appointment.getDentistId());
        appointment.cancel(now);
        appointmentEventRepository.save(AppointmentEvent.create(appointment.getId(),
                AppointmentEventType.APPOINTMENT_CANCELLED, actor.userId(),
                appointment.getOccurrenceVersion(), null));
        communications.cancelled(appointment, patient, dentist, actor.userId(),
                ZoneId.of(clinic.getTimezone()));
        return toResponse(appointment, patient, dentist);
    }

    @Transactional
    public AppointmentResponse complete(Long id, AuthenticatedUser actor) {
        Appointment appointment = appointmentRepository.findById(id)
                .orElseThrow(NotFoundException::new);
        requireScheduled(appointment);
        authorizeProfessionalTrajectory(appointment, actor);
        Instant now = clock.instant();
        Clinic clinic = clinicService.get();
        appointment.complete(now);
        appointmentEventRepository.save(AppointmentEvent.create(appointment.getId(),
                AppointmentEventType.APPOINTMENT_COMPLETED, actor.userId(),
                appointment.getOccurrenceVersion(), null));
        Patient patient = patientService.get(appointment.getPatientId());
        communications.notifyPatientInApp(appointment, patient, "Consulta completada",
                "Gracias por tu visita. Registramos tu consulta como completada.");
        Dentist dentist = dentistService.get(appointment.getDentistId());
        return toResponse(appointment, patient, dentist);
    }

    /** No-show: solo OWNER|RECEPTIONIST (matriz §17.5) y solo desde que la cita ya comenzó (A-18). */
    @Transactional
    public AppointmentResponse markNoShow(Long id, AuthenticatedUser actor) {
        Appointment appointment = appointmentRepository.findById(id)
                .orElseThrow(NotFoundException::new);
        requireScheduled(appointment);
        if (actor.role() == Role.PATIENT || actor.role() == Role.DENTIST) {
            throw new ForbiddenOperationException(
                    "Solo la recepción o la administración declaran un no-show.");
        }
        Instant now = clock.instant();
        if (now.isBefore(appointment.getStartAt())) {
            throw new ScheduleConflictException(
                    "Aún no puede declararse ausencia: la cita todavía no comenzó.");
        }
        appointment.markNoShow(now);
        appointmentEventRepository.save(AppointmentEvent.create(appointment.getId(),
                AppointmentEventType.APPOINTMENT_NO_SHOW, actor.userId(),
                appointment.getOccurrenceVersion(), null));
        Patient patient = patientService.get(appointment.getPatientId());
        String fecha = fechaHora(appointment.getStartAt(),
                ZoneId.of(clinicService.get().getTimezone()));
        communications.notifyPatientInApp(appointment, patient, "Cita no realizada",
                "No registramos tu asistencia a la cita del " + fecha + ". Si tienes dudas, contáctanos.");
        Dentist dentist = dentistService.get(appointment.getDentistId());
        return toResponse(appointment, patient, dentist);
    }

    /**
     * Llegada del paciente (P-01). Solo el profesional asignado la registra y una sola vez por
     * cita: el índice parcial UNIQUE de V006 convierte el duplicado en 409 (A-09/D-F6-24.b).
     */
    @Transactional
    public AppointmentEventView arrival(Long id, AuthenticatedUser actor) {
        Appointment appointment = appointmentRepository.findById(id)
                .orElseThrow(NotFoundException::new);
        requireScheduled(appointment);
        authorizeProfessionalTrajectory(appointment, actor);
        AppointmentEvent event = recordEvent(appointment,
                AppointmentEventType.PATIENT_ARRIVED, actor);
        Patient patient = patientService.get(appointment.getPatientId());
        Dentist dentist = dentistService.get(appointment.getDentistId());
        communications.notifyDentistInApp(appointment, dentist, "El paciente llegó",
                patient.getFirstName() + " " + patient.getLastName() + " llegó para su cita.");
        return toEventView(event);
    }

    /** Comienzo del tratamiento. Mismas reglas y deduplicación que la llegada. */
    @Transactional
    public AppointmentEventView start(Long id, AuthenticatedUser actor) {
        Appointment appointment = appointmentRepository.findById(id)
                .orElseThrow(NotFoundException::new);
        requireScheduled(appointment);
        authorizeProfessionalTrajectory(appointment, actor);
        AppointmentEvent event = recordEvent(appointment,
                AppointmentEventType.APPOINTMENT_STARTED, actor);
        Patient patient = patientService.get(appointment.getPatientId());
        Dentist dentist = dentistService.get(appointment.getDentistId());
        communications.notifyDentistInApp(appointment, dentist, "Cita en curso",
                "Comenzaste la cita con " + patient.getFirstName() + " "
                        + patient.getLastName() + ".");
        return toEventView(event);
    }

    private AppointmentEvent recordEvent(Appointment appointment, AppointmentEventType type,
                                         AuthenticatedUser actor) {
        return appointmentEventRepository.save(AppointmentEvent.create(appointment.getId(), type,
                actor.userId(), appointment.getOccurrenceVersion(), null));
    }

    @Transactional(readOnly = true)
    public AppointmentResponse view(Appointment appointment) {
        return toResponse(appointment, patientService.get(appointment.getPatientId()),
                dentistService.get(appointment.getDentistId()));
    }

    @Transactional(readOnly = true)
    public List<AppointmentResponse> views(List<Appointment> appointments) {
        if (appointments.isEmpty()) {
            return List.of();
        }
        Map<Long, Patient> patients = patientService.findByIds(
                appointments.stream().map(Appointment::getPatientId).toList());
        Map<Long, Dentist> dentists = dentistService.findByIds(
                appointments.stream().map(Appointment::getDentistId).toList());
        return appointments.stream()
                .map(a -> toResponse(a, patients.get(a.getPatientId()),
                        dentists.get(a.getDentistId())))
                .toList();
    }

    private void assertCanCreateFor(AuthenticatedUser actor, AppointmentData data) {
        switch (actor.role()) {
            case PATIENT -> {
                Long own = patientService.findPatientIdForUser(actor.userId())
                        .orElseThrow(() -> new ScheduleConflictException(
                                "Completa tu perfil de paciente antes de reservar una cita."));
                if (!own.equals(data.patientId())) {
                    throw new NotFoundException();
                }
            }
            case DENTIST -> {
                Long myDentistId = dentistService.dentistIdFor(actor.userId())
                        .orElseThrow(() -> new ForbiddenOperationException(
                                "Tu cuenta no tiene un perfil de dentista."));
                if (!myDentistId.equals(data.dentistId())) {
                    throw new ForbiddenOperationException(
                            "Solo puedes reservar citas contigo como profesional (A-15).");
                }
            }
            default -> {
                // OWNER y RECEPTIONIST agendan para cualquier paciente/profesional.
            }
        }
    }

    /** ¿El actor puede cancelar/reprogramar esta cita? PATIENT → solo la suya (404); DENTIST → la asignada (403). */
    private void assertCanControl(Appointment appointment, AuthenticatedUser actor) {
        switch (actor.role()) {
            case PATIENT -> {
                Long own = patientService.findPatientIdForUser(actor.userId()).orElse(null);
                if (own == null || !own.equals(appointment.getPatientId())) {
                    throw new NotFoundException();
                }
            }
            case DENTIST -> requireAssignedProfessional(appointment, actor);
            default -> {
                // OWNER y RECEPTIONIST gestionan cualquier cita.
            }
        }
    }

    /** Complejidad profesional (llegada/comienzo/finalización): solo el asignado, DENTIST u OWNER. */
    private void authorizeProfessionalTrajectory(Appointment appointment, AuthenticatedUser actor) {
        if (actor.role() != Role.DENTIST && actor.role() != Role.OWNER) {
            throw new ForbiddenOperationException(
                    "Solo el profesional asignado o la administración registran la llegada, el comienzo o la finalización.");
        }
        requireAssignedProfessional(appointment, actor);
    }

    private void requireAssignedProfessional(Appointment appointment, AuthenticatedUser actor) {
        Long myDentistId = dentistService.dentistIdFor(actor.userId())
                .orElseThrow(() -> new ForbiddenOperationException(
                        "Tu cuenta no tiene un perfil de dentista."));
        if (!myDentistId.equals(appointment.getDentistId())) {
            throw new ForbiddenOperationException(
                    "Esta cita no es tuya: solo el profesional asignado puede operar sobre ella.");
        }
    }

    private void requireVisibleTo(Appointment appointment, AuthenticatedUser actor) {
        if (actor.role() == Role.PATIENT) {
            Long own = patientService.findPatientIdForUser(actor.userId()).orElse(null);
            if (own == null || !own.equals(appointment.getPatientId())) {
                throw new NotFoundException();
            }
        }
    }

    private void requireScheduled(Appointment appointment) {
        if (appointment.getStatus() != AppointmentStatus.SCHEDULED) {
            throw new ScheduleConflictException(
                    "La cita ya no está programada (estado " + appointment.getStatus() + ").");
        }
    }

    private void requireActiveProfessional(Dentist dentist) {
        userService.findById(dentist.getUserId())
                .filter(user -> user.isActive())
                .orElseThrow(() -> new ScheduleConflictException(
                        "El profesional indicado no está disponible en este momento."));
    }

    /**
     * Validación de horizonte: futuro, plazo máximo de reserva (todos) y antelación mínima (solo
     * PATIENT, RF-AV-07). El encaje geométrico (ventanas/breaks/ocupación) lo resuelve {@link
     * AvailabilityService}.
     */
    private void validateBookingWindow(Instant now, Clinic clinic, ZoneId zone, Instant startAt,
                                       Role role) {
        if (!now.isBefore(startAt)) {
            throw new InvalidScheduleException("El horario de la cita debe estar en el futuro.");
        }
        long advanceDays = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(),
                startAt.atZone(zone).toLocalDate());
        if (advanceDays > clinic.getMaxAdvanceDays()) {
            throw new ScheduleConflictException("Solo se pueden reservar citas hasta "
                    + clinic.getMaxAdvanceDays() + " días antes.");
        }
        if (role == Role.PATIENT
                && Duration.between(now, startAt).toMinutes() < clinic.getMinBookingLeadTimeHours() * 60) {
            throw new ScheduleConflictException("Debes reservar con al menos "
                    + clinic.getMinBookingLeadTimeHours() + " horas de anticipación.");
        }
    }

    private boolean isBeforeCancellationDeadline(Clinic clinic, Instant now, Instant startAt) {
        Instant deadline = now.plus(Duration.ofHours(clinic.getCancellationLeadTimeHours()));
        return deadline.isAfter(startAt);
    }

    private static void validateRange(Instant startAt, Instant endAt) {
        if (startAt == null || endAt == null) {
            throw new InvalidScheduleException("Los límites de la cita (inicio y fin) son obligatorios.");
        }
        if (!startAt.isBefore(endAt)) {
            throw new InvalidScheduleException("La cita debe terminar después de empezar (end_at > start_at).");
        }
    }

    private static int durationMinutes(Instant startAt, Instant endAt) {
        long minutes = Duration.between(startAt, endAt).toMinutes();
        if (minutes <= 0 || minutes > Integer.MAX_VALUE) {
            throw new InvalidScheduleException("La duración de la cita no es válida.");
        }
        return (int) minutes;
    }

    private static String fechaHora(Instant instant, ZoneId zone) {
        return FECHA_HORA.format(instant.atZone(zone));
    }

    private static AppointmentResponse toResponse(Appointment a, Patient patient, Dentist dentist) {
        return new AppointmentResponse(a.getId(), a.getPatientId(), patient.getFirstName(),
                patient.getLastName(), patient.getUserId(), a.getDentistId(), dentist.getFirstName(),
                dentist.getLastName(), a.getStartAt(), a.getEndAt(), a.getDurationMinutes(),
                a.getStatus(), a.getOccurrenceVersion(), a.getCreatedByUserId(), a.getReason(),
                a.getNotes(), a.getCompletedAt(), a.getCreatedAt(), a.getUpdatedAt());
    }

    private static AppointmentEventView toEventView(AppointmentEvent event) {
        return new AppointmentEventView(event.getId(), event.getEventType(),
                event.getActorUserId(), event.getOccurrenceVersion(), event.getMetadata(),
                event.getCreatedAt());
    }

    public record AppointmentData(Long patientId, Long dentistId, Instant startAt, Instant endAt,
                                  String reason, String notes) {
    }

    public record RescheduleData(Instant startAt, Instant endAt, int expectedOccurrenceVersion) {
    }

    public record CreatedAppointment(AppointmentResponse response) {
    }
}
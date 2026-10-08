package com.dentalclinic.appointment.api;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dentalclinic.appointment.api.dto.AppointmentEventView;
import com.dentalclinic.appointment.api.dto.AppointmentResponse;
import com.dentalclinic.appointment.api.dto.CreateAppointmentRequest;
import com.dentalclinic.appointment.api.dto.RescheduleAppointmentRequest;
import com.dentalclinic.appointment.application.AppointmentCreateCoordinator;
import com.dentalclinic.appointment.application.AppointmentService;
import com.dentalclinic.appointment.application.AppointmentService.RescheduleData;
import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.appointment.domain.AppointmentEvent;
import com.dentalclinic.appointment.domain.AppointmentStatus;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.identity.infrastructure.security.CurrentUser;
import com.dentalclinic.patient.api.dto.PageResponse;

import jakarta.validation.Valid;

/**
 * Agenda de citas (Fase 6, §17.5). Ruta registrada en {@code identity.infrastructure.security}.
 *
 * <p>La autorización por propiedad no vive aquí: el servicio decide quién puede ver/crear/gestionar
 * cada cita (matriz §7.4/§8.2). Este controlador solo traduce HTTP a casos de uso.
 *
 * <p><strong>{@code POST /api/appointments}</strong> consume {@code Idempotency-Key} (A-16): si
 * falta, 400; si se repite con el mismo cuerpo, devuelve la respuesta original; con cuerpo distinto,
 * 409. El procesamiento completo —incluida la deduplicación— está en {@link
 * AppointmentCreateCoordinator}.
 */
@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AppointmentService appointmentService;
    private final AppointmentCreateCoordinator createCoordinator;

    public AppointmentController(AppointmentService appointmentService,
                                 AppointmentCreateCoordinator createCoordinator) {
        this.appointmentService = appointmentService;
        this.createCoordinator = createCoordinator;
    }

    /** Creación de cita idempotente (§17.5). El estado de respuesta es 201 vía {@code Idempotency-Key}. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AppointmentResponse create(@Valid @RequestBody CreateAppointmentRequest request,
                                      @RequestHeader(value = "Idempotency-Key", required = false)
                                      String idempotencyKey) {
        return createCoordinator.create(CurrentUser.require(), request, idempotencyKey);
    }

    /**
     * Agenda con filtros opcionales (RF-AP-10). Para un PATIENT, el servicio fuerza el filtro de
     * paciente a sí mismo. La paginación está acotada a 100 filas.
     */
    @GetMapping
    public PageResponse<AppointmentResponse> list(
            @RequestParam(required = false) Long dentistId,
            @RequestParam(required = false) Long patientId,
            @RequestParam(required = false) AppointmentStatus status,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Order.asc("startAt")));
        Page<Appointment> result = appointmentService.list(dentistId, patientId, status, from, to,
                CurrentUser.require(), pageable);
        return new PageResponse<>(appointmentService.views(result.getContent()),
                result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages());
    }

    @GetMapping("/{id}")
    public AppointmentResponse get(@PathVariable("id") Long id) {
        return appointmentService.view(appointmentService.get(id, CurrentUser.require()));
    }

    /** Auditoría append-only de la cita (RF-AP-05/15), misma visibilidad que la cita. */
    @GetMapping("/{id}/events")
    public List<AppointmentEventView> events(@PathVariable("id") Long id) {
        return appointmentService.events(id, CurrentUser.require()).stream()
                .map(AppointmentController::toEventView)
                .toList();
    }

    @PostMapping("/{id}/reschedule")
    public AppointmentResponse reschedule(@PathVariable("id") Long id,
                                          @Valid @RequestBody RescheduleAppointmentRequest request) {
        return appointmentService.reschedule(id, CurrentUser.require(),
                new RescheduleData(request.startAt(), request.endAt(),
                        request.expectedOccurrenceVersion()));
    }

    @PostMapping("/{id}/cancel")
    public AppointmentResponse cancel(@PathVariable("id") Long id) {
        return appointmentService.cancel(id, CurrentUser.require());
    }

    @PostMapping("/{id}/complete")
    public AppointmentResponse complete(@PathVariable("id") Long id) {
        return appointmentService.complete(id, CurrentUser.require());
    }

    /** Declaración de ausencia: solo OWNER|RECEPTIONIST y desde que la cita comenzó (A-18). */
    @PostMapping("/{id}/no-show")
    public AppointmentResponse noShow(@PathVariable("id") Long id) {
        return appointmentService.markNoShow(id, CurrentUser.require());
    }

    /** Llegada del paciente, registrada por el profesional asignado. 409 si ya se registró (A-09). */
    @PostMapping("/{id}/arrive")
    public AppointmentEventView arrive(@PathVariable("id") Long id) {
        return appointmentService.arrival(id, CurrentUser.require());
    }

    /** Comienzo del tratamiento, registrado por el profesional asignado. Misma deduplicación. */
    @PostMapping("/{id}/start")
    public AppointmentEventView start(@PathVariable("id") Long id) {
        return appointmentService.start(id, CurrentUser.require());
    }

    private static AppointmentEventView toEventView(AppointmentEvent event) {
        return new AppointmentEventView(event.getId(), event.getEventType(),
                event.getActorUserId(), event.getOccurrenceVersion(), event.getMetadata(),
                event.getCreatedAt());
    }
}
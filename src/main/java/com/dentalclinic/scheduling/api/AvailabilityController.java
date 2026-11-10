package com.dentalclinic.scheduling.api;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dentalclinic.scheduling.api.dto.AvailabilityResponse;
import com.dentalclinic.scheduling.api.dto.BookingPolicyUpdateRequest;
import com.dentalclinic.scheduling.api.dto.BookingPolicyView;
import com.dentalclinic.scheduling.application.AvailabilityService;
import com.dentalclinic.scheduling.application.BookingPolicyService;

import jakarta.validation.Valid;

/**
 * Disponibilidad calculada (RF-AV-01..12) y política de reservas.
 *
 * <p>{@code GET /api/availability} lo puede consultar cualquier rol con acceso de lectura al grupo,
 * incluido PATIENT. La escritura de la política es <strong>solo OWNER</strong>: el grupo
 * {@code /api/availability/**} da escritura a los roles internos, así que aquí se estrecha con
 * {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/availability")
public class AvailabilityController {

    private final AvailabilityService availabilityService;
    private final BookingPolicyService bookingPolicyService;
    private final com.dentalclinic.catalog.application.ServiceCatalogService catalog;
    private final com.dentalclinic.appointment.application.AppointmentService appointments;

    public AvailabilityController(AvailabilityService availabilityService,
            BookingPolicyService bookingPolicyService,
            com.dentalclinic.catalog.application.ServiceCatalogService catalog,
            com.dentalclinic.appointment.application.AppointmentService appointments) {
        this.availabilityService = availabilityService;
        this.bookingPolicyService = bookingPolicyService;
        this.catalog = catalog;
        this.appointments = appointments;
    }

    @GetMapping
    public AvailabilityResponse availability(
            @RequestParam("dentistId") Long dentistId,
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "duration", required = false) Integer duration,
            @RequestParam(name = "serviceId", required = false) Long serviceId,
            @RequestParam(name = "appointmentId", required = false) Long appointmentId) {
        if (serviceId != null && appointmentId != null) {
            throw new com.dentalclinic.scheduling.domain.exception.InvalidScheduleException("Consulta por servicio o por cita, no ambos.");
        }
        int resolvedDuration;
        if (appointmentId != null) {
            var appointment = appointments.get(appointmentId, com.dentalclinic.identity.infrastructure.security.CurrentUser.require());
            if (!appointment.getDentistId().equals(dentistId)) {
                throw new com.dentalclinic.scheduling.domain.exception.InvalidScheduleException("El profesional debe ser el de la cita.");
            }
            resolvedDuration = appointment.getDurationMinutes();
        } else if (serviceId != null) resolvedDuration = catalog.resolveActive(serviceId).durationMinutes();
        else if (duration != null) resolvedDuration = duration;
        else throw new com.dentalclinic.scheduling.domain.exception.InvalidScheduleException("Selecciona un servicio o consulta una cita existente.");
        // Reconsultar la disponibilidad de una cita existente sigue mostrando huecos aunque el
        // profesional se haya desactivado (el backend acepta reprogramarla); para reservas nuevas,
        // un profesional inactivo no se ofrece (H-A15).
        return availabilityService.compute(dentistId, from, to, resolvedDuration, appointmentId != null);
    }

    @GetMapping("/policy")
    public BookingPolicyView getPolicy() {
        return bookingPolicyService.get();
    }

    @PutMapping("/policy")
    @PreAuthorize("@authorization.isOwner(authentication.principal)")
    public BookingPolicyView updatePolicy(@Valid @RequestBody BookingPolicyUpdateRequest request) {
        return bookingPolicyService.update(request);
    }
}

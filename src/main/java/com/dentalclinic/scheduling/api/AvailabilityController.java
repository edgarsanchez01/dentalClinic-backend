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

    public AvailabilityController(AvailabilityService availabilityService,
            BookingPolicyService bookingPolicyService) {
        this.availabilityService = availabilityService;
        this.bookingPolicyService = bookingPolicyService;
    }

    @GetMapping
    public AvailabilityResponse availability(
            @RequestParam("dentistId") Long dentistId,
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam("duration") int duration) {
        return availabilityService.compute(dentistId, from, to, duration);
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
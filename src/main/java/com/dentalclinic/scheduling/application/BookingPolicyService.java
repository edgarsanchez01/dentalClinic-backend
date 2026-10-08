package com.dentalclinic.scheduling.application;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.clinic.application.ClinicService;
import com.dentalclinic.clinic.domain.Clinic;
import com.dentalclinic.scheduling.api.dto.BookingPolicyUpdateRequest;
import com.dentalclinic.scheduling.api.dto.BookingPolicyView;

/**
 * Política de reservas persistida en {@code clinic} (RF-AV-07/08/11). La autoridad es la fila de
 * {@code clinic}; no hay configuración de entorno ni de JVM.
 */
@Service
public class BookingPolicyService {

    private final ClinicService clinicService;
    private final Clock clock;

    public BookingPolicyService(ClinicService clinicService, Clock clock) {
        this.clinicService = clinicService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BookingPolicyView get() {
        return toView(clinicService.get());
    }

    @Transactional
    public BookingPolicyView update(BookingPolicyUpdateRequest request) {
        Clinic clinic = clinicService.get();
        clinic.updateBookingPolicy(request.maxAdvanceDays(), request.minBookingLeadTimeHours(),
                request.cancellationLeadTimeHours(), clock.instant());
        return toView(clinic);
    }

    private static BookingPolicyView toView(Clinic clinic) {
        return new BookingPolicyView(clinic.getMaxAdvanceDays(), clinic.getMinBookingLeadTimeHours(),
                clinic.getCancellationLeadTimeHours(), clinic.getTimezone());
    }
}
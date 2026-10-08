package com.dentalclinic.appointment.application;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.dentalclinic.appointment.domain.AppointmentStatus;
import com.dentalclinic.appointment.infrastructure.repository.AppointmentRepository;
import com.dentalclinic.scheduling.application.AppointmentOccupancyReader;

/**
 * Implementación del puerto de ocupación del módulo de citas (Fase 6).
 *
 * <p>Lives en la capa de aplicación de {@code appointment} e importa {@code scheduling.application}
 * (dirección de dependencia permitida por §6.3/§6.4): así Scheduling no depende de Appointments y
 * el {@link AppointmentOccupancyReader} es solo una interfaz en su propio módulo.
 *
 * <p>Usa el mismo predicado {@code [inicio, fin) ∧ SCHEDULED} que la constraint EXCLUDE de V006
 * (A-14): disponibilidad calculada y reserva real no pueden divergir (Architecture.md 10.3/10.4).
 */
@Service
public class AppointmentOccupancyServiceImpl implements AppointmentOccupancyReader {

    private final AppointmentRepository appointmentRepository;

    public AppointmentOccupancyServiceImpl(AppointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    @Override
    public List<OccupiedInterval> overlapping(Long dentistId, Instant startAt, Instant endAt,
                                              Long excludeAppointmentId) {
        return appointmentRepository
                .findScheduledOverlapping(dentistId, AppointmentStatus.SCHEDULED, startAt, endAt)
                .stream()
                .filter(a -> excludeAppointmentId == null
                        || !excludeAppointmentId.equals(a.getId()))
                .map(a -> new OccupiedInterval(a.getId(), a.getStartAt(), a.getEndAt()))
                .toList();
    }

    @Override
    public List<OccupiedInterval> occupiedIntervalsInRange(Long dentistId, Instant from,
                                                           Instant to) {
        return appointmentRepository
                .findScheduledOverlapping(dentistId, AppointmentStatus.SCHEDULED, from, to)
                .stream()
                .map(a -> new OccupiedInterval(a.getId(), a.getStartAt(), a.getEndAt()))
                .toList();
    }
}
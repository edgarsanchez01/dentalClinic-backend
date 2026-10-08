package com.dentalclinic.appointment.infrastructure.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dentalclinic.appointment.domain.AppointmentEvent;

public interface AppointmentEventRepository extends JpaRepository<AppointmentEvent, Long> {

    /** Auditoría de una cita en orden cronológico de registro (RF-AP-05/15). */
    List<AppointmentEvent> findByAppointmentIdOrderByCreatedAtAscIdAsc(Long appointmentId);
}
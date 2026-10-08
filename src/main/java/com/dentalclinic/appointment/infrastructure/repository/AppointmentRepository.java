package com.dentalclinic.appointment.infrastructure.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.appointment.domain.AppointmentStatus;

public interface AppointmentRepository extends JpaRepository<Appointment, Long>,
        JpaSpecificationExecutor<Appointment> {

    /**
     * Citas de un dentista cuyo intervalo [start, end) solapa [startAt, endAt) en sentido estricto
     * del medio-abierto: {@code startAt < end && start < endAt} (RF-AV-06).
     *
     * <p>Es el MISMO predicado que la constraint EXCLUDE de V006 (A-14) y el que usa la
     * disponibilidad calculada (Architecture.md 10.3/10.4), filtrando por el estado que ocupa slot.
     */
    @Query("select a from Appointment a "
            + "where a.dentistId = :dentistId and a.status = :status "
            + "and a.startAt < :endAt and a.endAt > :startAt order by a.startAt")
    List<Appointment> findScheduledOverlapping(@Param("dentistId") Long dentistId,
            @Param("status") AppointmentStatus status, @Param("startAt") Instant startAt,
            @Param("endAt") Instant endAt);
}

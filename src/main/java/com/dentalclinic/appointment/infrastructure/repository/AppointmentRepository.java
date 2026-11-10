package com.dentalclinic.appointment.infrastructure.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.appointment.domain.AppointmentStatus;

public interface AppointmentRepository extends JpaRepository<Appointment, Long>,
        JpaSpecificationExecutor<Appointment> {

    /** Proyección pequeña y paginación por id: no carga entidades ni necesita un count global. */
    interface ReminderCandidate {
        Long getId();
        int getOccurrenceVersion();
    }

    @Query(value = """
            SELECT a.id AS id, a.occurrence_version AS "occurrenceVersion"
            FROM appointment a
            WHERE a.id > :afterId AND a.status = 'SCHEDULED'
              AND a.start_at > :now AND a.start_at <= :windowEnd
              AND NOT EXISTS (
                  SELECT 1 FROM communication_intention ci
                  WHERE ci.appointment_id = a.id AND ci.event_type = 'REMINDER'
                    AND ci.occurrence_version = a.occurrence_version
                    AND ci.required_role = 'PATIENT'
              )
            ORDER BY a.id
            LIMIT :batchSize
            """, nativeQuery = true)
    List<ReminderCandidate> findReminderCandidates(@Param("now") Instant now,
            @Param("windowEnd") Instant windowEnd, @Param("afterId") long afterId,
            @Param("batchSize") int batchSize);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Appointment a where a.id = :id")
    Optional<Appointment> findByIdForUpdate(@Param("id") Long id);

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

package com.dentalclinic.appointment.infrastructure.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dentalclinic.appointment.domain.Communication;
import com.dentalclinic.appointment.domain.CommunicationStatus;
import com.dentalclinic.appointment.domain.CommunicationType;

public interface CommunicationRepository extends JpaRepository<Communication, Long> {

    /** Orden de bloqueo cuando se usa con la cita: appointment primero, communication después. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Communication c where c.id = :id")
    Optional<Communication> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Communication c where c.appointmentId = :appointmentId "
            + "and c.type = com.dentalclinic.appointment.domain.CommunicationType.REMINDER "
            + "and c.status = com.dentalclinic.appointment.domain.CommunicationStatus.PENDING "
            + "and c.supersededAt is null and c.occurrenceVersion <= :version order by c.id")
    List<Communication> lockPendingReminders(@Param("appointmentId") Long appointmentId,
            @Param("version") int version);

    /**
     * Barrido acotado, independiente de SMTP/flag/lease. Solo bloquea comunicaciones; la lectura
     * MVCC de appointment no adquiere locks, evitando invertir el orden appointment→communication.
     */
    @Query(value = """
            SELECT c.id FROM communication c
            WHERE c.type = 'REMINDER' AND c.status = 'PENDING' AND c.superseded_at IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM appointment a WHERE a.id = c.appointment_id
                    AND a.status = 'SCHEDULED' AND a.occurrence_version = c.occurrence_version
                    AND a.start_at > :now
              )
            ORDER BY c.id LIMIT :batchSize
            FOR UPDATE OF c SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockInvalidReminders(@Param("now") Instant now, @Param("batchSize") int batchSize);

    /**
     * Comunicaciones de una ocurrencia anterior que siguen vigentes (PENDING y sin anular) y deben
     * marcarse como {@code superseded_at} al reprogramar de nuevo (A-07/D-F6-20).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Communication c where c.appointmentId = :appointmentId "
            + "and c.type = :type and c.status = :status and c.supersededAt is null "
            + "and c.occurrenceVersion < :version order by c.id")
    List<Communication> findSupersedable(@Param("appointmentId") Long appointmentId,
            @Param("type") CommunicationType type, @Param("status") CommunicationStatus status,
            @Param("version") int version);

    /**
     * Claim atómico del dispatcher (Architecture.md 9.1/9.4): selecciona las filas {@code PENDING}
     * vigentes y elegibles ({@code nextRetryAt} vencido o nulo) y las bloquea con
     * {@code FOR UPDATE SKIP LOCKED}, de modo que dos instancias concurrentes nunca reclamen la
     * misma fila. El bloqueo se libera al commit de la transacción corta de reclamo; el envío SMTP
     * ocurre después, fuera de toda transacción.
     */
    @Query(value = """
            SELECT id FROM communication
            WHERE status = 'PENDING'
              AND superseded_at IS NULL
              AND (next_retry_at IS NULL OR next_retry_at <= :now)
              AND (:reminderEmailEnabled OR type <> 'REMINDER')
            ORDER BY next_retry_at ASC NULLS FIRST, id ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> lockClaimable(@Param("now") Instant now, @Param("batchSize") int batchSize,
            @Param("reminderEmailEnabled") boolean reminderEmailEnabled);
}

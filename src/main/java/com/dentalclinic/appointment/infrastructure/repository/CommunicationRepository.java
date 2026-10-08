package com.dentalclinic.appointment.infrastructure.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dentalclinic.appointment.domain.Communication;
import com.dentalclinic.appointment.domain.CommunicationStatus;
import com.dentalclinic.appointment.domain.CommunicationType;

public interface CommunicationRepository extends JpaRepository<Communication, Long> {

    /**
     * Comunicaciones de una ocurrencia anterior que siguen vigentes (PENDING y sin anular) y deben
     * marcarse como {@code superseded_at} al reprogramar de nuevo (A-07/D-F6-20).
     */
    @Query("select c from Communication c where c.appointmentId = :appointmentId "
            + "and c.type = :type and c.status = :status and c.supersededAt is null "
            + "and c.occurrenceVersion < :version")
    List<Communication> findSupersedable(@Param("appointmentId") Long appointmentId,
            @Param("type") CommunicationType type, @Param("status") CommunicationStatus status,
            @Param("version") int version);
}
package com.dentalclinic.appointment.infrastructure.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dentalclinic.appointment.domain.CommunicationIntention;
import com.dentalclinic.appointment.domain.CommunicationIntention.RequiredRole;
import com.dentalclinic.appointment.domain.CommunicationType;

public interface CommunicationIntentionRepository
         extends JpaRepository<CommunicationIntention, Long> {

    boolean existsByAppointmentIdAndEventTypeAndOccurrenceVersionAndRequiredRole(
            Long appointmentId, CommunicationType eventType, int occurrenceVersion,
            RequiredRole requiredRole);
}

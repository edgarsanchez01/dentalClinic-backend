package com.dentalclinic.appointment.infrastructure.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dentalclinic.appointment.domain.CommunicationIntention;

public interface CommunicationIntentionRepository
        extends JpaRepository<CommunicationIntention, Long> {
}
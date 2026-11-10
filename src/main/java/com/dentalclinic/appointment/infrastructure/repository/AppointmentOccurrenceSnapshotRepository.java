package com.dentalclinic.appointment.infrastructure.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.dentalclinic.appointment.domain.AppointmentOccurrenceSnapshot;
import com.dentalclinic.appointment.domain.OccurrenceKey;

public interface AppointmentOccurrenceSnapshotRepository extends JpaRepository<AppointmentOccurrenceSnapshot, OccurrenceKey> { }

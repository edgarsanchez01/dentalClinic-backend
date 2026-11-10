package com.dentalclinic.clinical.infrastructure.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dentalclinic.clinical.domain.Treatment;

public interface TreatmentRepository extends JpaRepository<Treatment, Long> {

    List<Treatment> findByPatientIdOrderByCreatedAtDescIdDesc(Long patientId);

    Optional<Treatment> findByIdAndPatientId(Long id, Long patientId);
}

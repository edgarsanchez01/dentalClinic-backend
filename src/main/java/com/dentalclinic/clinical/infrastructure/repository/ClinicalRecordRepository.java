package com.dentalclinic.clinical.infrastructure.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dentalclinic.clinical.domain.ClinicalRecord;

public interface ClinicalRecordRepository extends JpaRepository<ClinicalRecord, Long> {

    Optional<ClinicalRecord> findByPatientId(Long patientId);
}
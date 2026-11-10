package com.dentalclinic.clinical.infrastructure.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.dentalclinic.clinical.domain.ClinicalRecord;

public interface ClinicalRecordRepository extends JpaRepository<ClinicalRecord, Long> {

    Optional<ClinicalRecord> findByPatientId(Long patientId);

    /** Waits for competing inserts, without replacing any existing clinical fields. Caller transaction owns it. */
    @Modifying
    @Query(value = "INSERT INTO clinical_record (patient_id) VALUES (:patientId) ON CONFLICT (patient_id) DO NOTHING", nativeQuery = true)
    void ensureEmptyRecord(Long patientId);
}

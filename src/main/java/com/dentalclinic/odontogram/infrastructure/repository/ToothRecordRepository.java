package com.dentalclinic.odontogram.infrastructure.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import com.dentalclinic.odontogram.domain.ToothRecord;

public interface ToothRecordRepository extends JpaRepository<ToothRecord, Long> {

    List<ToothRecord> findByPatientIdOrderByToothFdiAsc(Long patientId);

    List<ToothRecord> findByPatientIdAndIdIn(Long patientId, Collection<Long> ids);

    Optional<ToothRecord> findByPatientIdAndToothFdi(Long patientId, int toothFdi);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ToothRecord t where t.patientId=:patientId and t.toothFdi=:fdi")
    Optional<ToothRecord> findForUpdate(@Param("patientId") Long patientId, @Param("fdi") int fdi);

    boolean existsByPatientIdAndToothFdi(Long patientId, int toothFdi);
}

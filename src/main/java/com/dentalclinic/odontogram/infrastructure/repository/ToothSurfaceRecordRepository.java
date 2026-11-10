package com.dentalclinic.odontogram.infrastructure.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import com.dentalclinic.odontogram.domain.ToothSurface;
import com.dentalclinic.odontogram.domain.ToothSurfaceRecord;

public interface ToothSurfaceRecordRepository extends JpaRepository<ToothSurfaceRecord, Long> {

    List<ToothSurfaceRecord> findByToothRecordIdOrderBySurfaceAsc(Long toothRecordId);

    List<ToothSurfaceRecord> findByToothRecordIdInOrderBySurfaceAsc(List<Long> toothRecordIds);

    Optional<ToothSurfaceRecord> findByToothRecordIdAndSurface(Long toothRecordId, ToothSurface surface);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ToothSurfaceRecord s where s.toothRecordId=:tooth and s.surface=:surface")
    Optional<ToothSurfaceRecord> findForUpdate(@Param("tooth") Long tooth, @Param("surface") ToothSurface surface);

    boolean existsByToothRecordIdAndSurface(Long toothRecordId, ToothSurface surface);
}

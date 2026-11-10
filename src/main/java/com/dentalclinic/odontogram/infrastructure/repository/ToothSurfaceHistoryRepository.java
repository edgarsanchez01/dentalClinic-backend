package com.dentalclinic.odontogram.infrastructure.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dentalclinic.odontogram.domain.ToothSurfaceHistory;

public interface ToothSurfaceHistoryRepository extends JpaRepository<ToothSurfaceHistory, Long> {

    List<ToothSurfaceHistory> findByToothSurfaceRecordIdOrderByChangedAtAscIdAsc(Long toothSurfaceRecordId);

    List<ToothSurfaceHistory> findByToothSurfaceRecordIdInOrderByChangedAtAscIdAsc(List<Long> toothSurfaceRecordIds);
}
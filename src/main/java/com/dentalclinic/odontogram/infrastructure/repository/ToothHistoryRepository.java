package com.dentalclinic.odontogram.infrastructure.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dentalclinic.odontogram.domain.ToothHistory;

public interface ToothHistoryRepository extends JpaRepository<ToothHistory, Long> {

    List<ToothHistory> findByToothRecordIdOrderByChangedAtAscIdAsc(Long toothRecordId);

    List<ToothHistory> findByToothRecordIdInOrderByChangedAtAscIdAsc(List<Long> toothRecordIds);

    long deleteByToothRecordId(Long toothRecordId);
}

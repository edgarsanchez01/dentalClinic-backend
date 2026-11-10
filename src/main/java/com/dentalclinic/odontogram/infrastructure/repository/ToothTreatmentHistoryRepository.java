package com.dentalclinic.odontogram.infrastructure.repository;

import java.util.List;
import org.springframework.data.repository.Repository;
import com.dentalclinic.odontogram.domain.ToothTreatmentHistory;

public interface ToothTreatmentHistoryRepository extends Repository<ToothTreatmentHistory, Long> {
    ToothTreatmentHistory save(ToothTreatmentHistory event);
    List<ToothTreatmentHistory> findByToothRecordIdOrderByChangedAtAscIdAsc(Long toothRecordId);
}

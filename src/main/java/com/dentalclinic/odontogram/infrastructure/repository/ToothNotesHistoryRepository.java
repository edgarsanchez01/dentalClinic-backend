package com.dentalclinic.odontogram.infrastructure.repository;

import java.util.List;
import org.springframework.data.repository.Repository;
import com.dentalclinic.odontogram.domain.ToothNotesHistory;

/** Insert/read only; cleanup belongs to isolated test fixtures, never application writers. */
public interface ToothNotesHistoryRepository extends Repository<ToothNotesHistory, Long> {
    ToothNotesHistory save(ToothNotesHistory event);
    List<ToothNotesHistory> findByToothRecordIdOrderByChangedAtAscIdAsc(Long toothRecordId);
}

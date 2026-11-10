package com.dentalclinic.odontogram.infrastructure.repository;

import java.util.List;
import org.springframework.data.repository.Repository;
import com.dentalclinic.odontogram.domain.ToothSurfaceNotesHistory;

public interface ToothSurfaceNotesHistoryRepository extends Repository<ToothSurfaceNotesHistory, Long> {
    ToothSurfaceNotesHistory save(ToothSurfaceNotesHistory event);
    List<ToothSurfaceNotesHistory> findByToothSurfaceRecordIdOrderByChangedAtAscIdAsc(Long surfaceId);
}

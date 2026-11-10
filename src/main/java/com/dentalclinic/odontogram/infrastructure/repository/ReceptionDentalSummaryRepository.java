package com.dentalclinic.odontogram.infrastructure.repository;

import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import com.dentalclinic.odontogram.domain.ToothCondition;
import com.dentalclinic.odontogram.domain.ToothRecord;
import com.dentalclinic.odontogram.domain.ToothSurface;

/** Scalar allowlist: no entity hydration, private notes, treatment links or history queries. */
public interface ReceptionDentalSummaryRepository extends Repository<ToothRecord, Long> {
    @Query("""
            select t.toothFdi as toothFdi, t.condition as condition,
                   s.surface as surface, s.condition as surfaceCondition
            from ToothRecord t left join ToothSurfaceRecord s on s.toothRecordId = t.id
            where t.patientId = :patientId
            order by t.toothFdi, s.surface
            """)
    List<SummaryRow> findCurrentSummary(@Param("patientId") Long patientId);

    interface SummaryRow {
        int getToothFdi();
        ToothCondition getCondition();
        ToothSurface getSurface();
        ToothCondition getSurfaceCondition();
    }
}

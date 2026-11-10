package com.dentalclinic.odontogram.infrastructure.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dentalclinic.odontogram.domain.TreatmentTooth;

public interface TreatmentToothRepository extends JpaRepository<TreatmentTooth, Long> {

    List<TreatmentTooth> findByToothRecordId(Long toothRecordId);

    List<TreatmentTooth> findByToothRecordIdIn(List<Long> toothRecordIds);

    List<TreatmentTooth> findByTreatmentIdIn(Collection<Long> treatmentIds);

    long deleteByToothRecordId(Long toothRecordId);
}

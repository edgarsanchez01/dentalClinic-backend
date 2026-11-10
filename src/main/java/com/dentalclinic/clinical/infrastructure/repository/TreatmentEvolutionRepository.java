package com.dentalclinic.clinical.infrastructure.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.clinical.domain.TreatmentEvolution;

public interface TreatmentEvolutionRepository extends JpaRepository<TreatmentEvolution, Long> {

    List<TreatmentEvolution> findByTreatmentIdOrderByCreatedAtAscIdAsc(Long treatmentId);

    List<TreatmentEvolution> findByTreatmentIdAndAppointmentContextIsNullOrderByCreatedAtAscIdAsc(Long treatmentId);

    Optional<TreatmentEvolution> findByIdAndTreatmentId(Long id, Long treatmentId);

    /** Correcciones de una pagina de entradas raiz, en una sola consulta (evita N+1). */
    List<TreatmentEvolution> findByCorrectsEvolutionIdInOrderByCreatedAtAscIdAsc(Collection<Long> correctsEvolutionIds);

    @Query("select e from TreatmentEvolution e join Treatment t on t.id = e.treatmentId "
            + "where t.patientId = :patientId and e.appointmentContext.appointmentId = :appointmentId "
            + "and e.appointmentContext.occurrenceVersion = :version and e.correctsEvolutionId is null")
    Page<TreatmentEvolution> findAttention(Long patientId, Long appointmentId, int version, Pageable pageable);

    /** Limpieza de pruebas: borra correcciones antes que sus raices (auto-FK RESTRICT). */
    @Modifying
    @Transactional
    @Query("delete from TreatmentEvolution e where e.correctsEvolutionId is not null")
    void deleteCorrections();
}

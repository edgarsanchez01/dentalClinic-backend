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

import com.dentalclinic.clinical.domain.ClinicalNote;

public interface ClinicalNoteRepository extends JpaRepository<ClinicalNote, Long> {

    List<ClinicalNote> findByClinicalRecordIdOrderByCreatedAtAscIdAsc(Long clinicalRecordId);

    List<ClinicalNote> findByClinicalRecordIdAndAppointmentContextIsNullOrderByCreatedAtAscIdAsc(Long clinicalRecordId);

    Optional<ClinicalNote> findByIdAndClinicalRecordId(Long id, Long clinicalRecordId);

    /** Correcciones de una pagina de notas raiz, en una sola consulta (evita N+1). */
    List<ClinicalNote> findByCorrectsNoteIdInOrderByCreatedAtAscIdAsc(Collection<Long> correctsNoteIds);

    @Query("select n from ClinicalNote n join ClinicalRecord r on r.id = n.clinicalRecordId "
            + "where r.patientId = :patientId and n.appointmentContext.appointmentId = :appointmentId "
            + "and n.appointmentContext.occurrenceVersion = :version and n.correctsNoteId is null")
    Page<ClinicalNote> findAttention(Long patientId, Long appointmentId, int version, Pageable pageable);

    /** Limpieza de pruebas: borra correcciones antes que sus raices (auto-FK RESTRICT). */
    @Modifying
    @Transactional
    @Query("delete from ClinicalNote n where n.correctsNoteId is not null")
    void deleteCorrections();
}

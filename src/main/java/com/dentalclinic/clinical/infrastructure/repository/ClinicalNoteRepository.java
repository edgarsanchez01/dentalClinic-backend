package com.dentalclinic.clinical.infrastructure.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dentalclinic.clinical.domain.ClinicalNote;

public interface ClinicalNoteRepository extends JpaRepository<ClinicalNote, Long> {

    List<ClinicalNote> findByClinicalRecordIdOrderByCreatedAtAscIdAsc(Long clinicalRecordId);
}
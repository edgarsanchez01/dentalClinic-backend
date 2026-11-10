package com.dentalclinic.medicalintake.infrastructure;

import java.util.Optional;
import java.util.UUID;
import com.dentalclinic.medicalintake.domain.IntakeDeclaration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntakeDeclarationRepository extends JpaRepository<IntakeDeclaration, Long> {
    Optional<IntakeDeclaration> findByPatientIdAndOperationId(Long patientId, UUID operationId);
    Optional<IntakeDeclaration> findFirstByPatientIdOrderByRevisionDesc(Long patientId);
    Page<IntakeDeclaration> findByPatientIdOrderByRevisionDesc(Long patientId, Pageable pageable);
}

package com.dentalclinic.medicalintake.infrastructure;

import java.util.Optional;
import com.dentalclinic.medicalintake.domain.IntakePrivacyEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntakePrivacyRepository extends JpaRepository<IntakePrivacyEvidence, Long> {
    Optional<IntakePrivacyEvidence> findByPatientIdAndNoticeVersion(Long patientId, String noticeVersion);
}

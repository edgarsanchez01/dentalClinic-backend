package com.dentalclinic.medicalintake.infrastructure;

import com.dentalclinic.medicalintake.domain.IntakeDraft;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntakeDraftRepository extends JpaRepository<IntakeDraft, Long> { }

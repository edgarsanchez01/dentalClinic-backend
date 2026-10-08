package com.dentalclinic.scheduling.infrastructure.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dentalclinic.scheduling.domain.ClinicScheduleException;

public interface ClinicScheduleExceptionRepository extends JpaRepository<ClinicScheduleException, Long> {

    List<ClinicScheduleException> findByClinicIdOrderByExceptionDateAsc(Long clinicId);

    Optional<ClinicScheduleException> findByClinicIdAndExceptionDate(Long clinicId, LocalDate exceptionDate);

    void deleteByClinicIdAndExceptionDate(Long clinicId, LocalDate exceptionDate);
}
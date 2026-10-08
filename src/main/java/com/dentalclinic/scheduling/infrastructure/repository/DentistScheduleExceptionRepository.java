package com.dentalclinic.scheduling.infrastructure.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dentalclinic.scheduling.domain.DentistScheduleException;

public interface DentistScheduleExceptionRepository extends JpaRepository<DentistScheduleException, Long> {

    List<DentistScheduleException> findByDentistIdOrderByExceptionDateAsc(Long dentistId);

    Optional<DentistScheduleException> findByDentistIdAndExceptionDate(Long dentistId, LocalDate exceptionDate);

    void deleteByDentistIdAndExceptionDate(Long dentistId, LocalDate exceptionDate);
}
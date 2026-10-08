package com.dentalclinic.scheduling.infrastructure.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dentalclinic.scheduling.domain.DentistTimeOff;

public interface DentistTimeOffRepository extends JpaRepository<DentistTimeOff, Long> {

    List<DentistTimeOff> findByDentistIdOrderByStartDateDesc(Long dentistId);

    List<DentistTimeOff> findByDentistIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            Long dentistId, LocalDate to, LocalDate from);

    Optional<DentistTimeOff> findByIdAndDentistId(Long id, Long dentistId);
}
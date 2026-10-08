package com.dentalclinic.scheduling.infrastructure.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dentalclinic.scheduling.domain.DentistSchedule;

public interface DentistScheduleRepository extends JpaRepository<DentistSchedule, Long> {

    List<DentistSchedule> findByDentistIdOrderByDayOfWeekAsc(Long dentistId);

    Optional<DentistSchedule> findByDentistIdAndDayOfWeek(Long dentistId, int dayOfWeek);

    void deleteByDentistId(Long dentistId);

    void deleteByDentistIdAndDayOfWeek(Long dentistId, int dayOfWeek);
}
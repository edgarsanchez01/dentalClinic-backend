package com.dentalclinic.scheduling.infrastructure.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dentalclinic.scheduling.domain.ClinicSchedule;

public interface ClinicScheduleRepository extends JpaRepository<ClinicSchedule, Long> {

    List<ClinicSchedule> findByClinicIdOrderByDayOfWeekAsc(Long clinicId);

    Optional<ClinicSchedule> findByClinicIdAndDayOfWeek(Long clinicId, int dayOfWeek);

    void deleteByClinicId(Long clinicId);

    void deleteByClinicIdAndDayOfWeek(Long clinicId, int dayOfWeek);
}
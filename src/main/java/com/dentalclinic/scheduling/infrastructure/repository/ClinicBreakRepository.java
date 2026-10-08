package com.dentalclinic.scheduling.infrastructure.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dentalclinic.scheduling.domain.ClinicBreak;

public interface ClinicBreakRepository extends JpaRepository<ClinicBreak, Long> {

    List<ClinicBreak> findByClinicIdOrderByDayOfWeekAscStartTimeAsc(Long clinicId);

    List<ClinicBreak> findByClinicIdAndDayOfWeekOrderByStartTimeAsc(Long clinicId, int dayOfWeek);

    void deleteByClinicId(Long clinicId);

    void deleteByClinicIdAndDayOfWeek(Long clinicId, int dayOfWeek);
}
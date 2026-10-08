package com.dentalclinic.scheduling.infrastructure.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dentalclinic.scheduling.domain.DentistBreak;

public interface DentistBreakRepository extends JpaRepository<DentistBreak, Long> {

    List<DentistBreak> findByDentistIdOrderByDayOfWeekAscStartTimeAsc(Long dentistId);

    List<DentistBreak> findByDentistIdAndDayOfWeekOrderByStartTimeAsc(Long dentistId, int dayOfWeek);

    void deleteByDentistId(Long dentistId);

    void deleteByDentistIdAndDayOfWeek(Long dentistId, int dayOfWeek);
}
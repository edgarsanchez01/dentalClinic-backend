package com.dentalclinic.scheduling.application;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.clinic.application.ClinicService;
import com.dentalclinic.clinic.domain.Clinic;
import com.dentalclinic.scheduling.api.dto.ClinicScheduleView;
import com.dentalclinic.scheduling.api.dto.DayRequest;
import com.dentalclinic.scheduling.api.dto.ScheduleDayView;
import com.dentalclinic.scheduling.api.dto.ScheduleExceptionRequest;
import com.dentalclinic.scheduling.api.dto.ScheduleExceptionView;
import com.dentalclinic.scheduling.api.dto.TimeRangeRequest;
import com.dentalclinic.scheduling.api.dto.TimeRangeView;
import com.dentalclinic.scheduling.api.dto.WeekDayRequest;
import com.dentalclinic.scheduling.api.dto.WeekRequest;
import com.dentalclinic.scheduling.domain.ClinicBreak;
import com.dentalclinic.scheduling.domain.ClinicSchedule;
import com.dentalclinic.scheduling.domain.ClinicScheduleException;
import com.dentalclinic.scheduling.domain.exception.InvalidScheduleException;
import com.dentalclinic.scheduling.infrastructure.repository.ClinicBreakRepository;
import com.dentalclinic.scheduling.infrastructure.repository.ClinicScheduleExceptionRepository;
import com.dentalclinic.scheduling.infrastructure.repository.ClinicScheduleRepository;

/**
 * Configuración del horario semanal de la clínica y sus excepciones (RF-SC-01/05).
 *
 * <p>La sustitución de la semana es <strong>atómica</strong>: se valida el estado final completo y
 * solo entonces se reemplazan filas, dentro de una única transacción. Los días son ISO-8601.
 */
@Service
public class ClinicScheduleService {

    private final ClinicService clinicService;
    private final ClinicScheduleRepository scheduleRepository;
    private final ClinicBreakRepository breakRepository;
    private final ClinicScheduleExceptionRepository exceptionRepository;

    public ClinicScheduleService(ClinicService clinicService, ClinicScheduleRepository scheduleRepository,
            ClinicBreakRepository breakRepository, ClinicScheduleExceptionRepository exceptionRepository) {
        this.clinicService = clinicService;
        this.scheduleRepository = scheduleRepository;
        this.breakRepository = breakRepository;
        this.exceptionRepository = exceptionRepository;
    }

    @Transactional(readOnly = true)
    public ClinicScheduleView get() {
        Clinic clinic = clinicService.get();
        return buildView(clinic.getId(), clinic.getTimezone());
    }

    @Transactional
    public ClinicScheduleView replaceWeek(WeekRequest request) {
        Clinic clinic = clinicService.get();
        SortedMap<Integer, ScheduleRules.DayRule> week = ScheduleRules.validateWeek(request.days());
        persistWeek(clinic.getId(), week);
        return buildView(clinic.getId(), clinic.getTimezone());
    }

    @Transactional
    public ClinicScheduleView replaceDay(int dayOfWeek, DayRequest request) {
        Clinic clinic = clinicService.get();
        ScheduleRules.DayRule rule = ScheduleRules.validateDay(dayOfWeek, request.open(), request.startTime(),
                request.endTime(), request.breaks());
        Long clinicId = clinic.getId();
        scheduleRepository.deleteByClinicIdAndDayOfWeek(clinicId, dayOfWeek);
        breakRepository.deleteByClinicIdAndDayOfWeek(clinicId, dayOfWeek);
        scheduleRepository.flush();
        breakRepository.flush();
        if (rule.start() != null) {
            scheduleRepository.save(ClinicSchedule.create(clinicId, dayOfWeek, rule.start(), rule.end()));
            for (LocalTime[] b : rule.breaks()) {
                breakRepository.save(ClinicBreak.create(clinicId, dayOfWeek, b[0], b[1]));
            }
        }
        return buildView(clinicId, clinic.getTimezone());
    }

    @Transactional(readOnly = true)
    public List<ScheduleExceptionView> listExceptions() {
        Long clinicId = clinicService.get().getId();
        return exceptionRepository.findByClinicIdOrderByExceptionDateAsc(clinicId).stream()
                .map(ClinicScheduleService::toExceptionView)
                .toList();
    }

    @Transactional
    public ScheduleExceptionView putException(LocalDate date, ScheduleExceptionRequest request) {
        Long clinicId = clinicService.get().getId();
        LocalTime start = null;
        LocalTime end = null;
        if (!request.closed()) {
            start = request.startTime();
            end = request.endTime();
            if (start == null || end == null || !start.isBefore(end)) {
                throw new InvalidScheduleException("Una excepción abierta debe tener inicio anterior al fin.");
            }
        }
        String reason = normalize(request.reason());
        exceptionRepository.deleteByClinicIdAndExceptionDate(clinicId, date);
        exceptionRepository.flush();
        ClinicScheduleException saved = exceptionRepository.save(
                ClinicScheduleException.create(clinicId, date, request.closed(), start, end, reason));
        return toExceptionView(saved);
    }

    @Transactional
    public void deleteException(LocalDate date) {
        Long clinicId = clinicService.get().getId();
        exceptionRepository.deleteByClinicIdAndExceptionDate(clinicId, date);
    }

    private void persistWeek(Long clinicId, SortedMap<Integer, ScheduleRules.DayRule> week) {
        scheduleRepository.deleteByClinicId(clinicId);
        breakRepository.deleteByClinicId(clinicId);
        scheduleRepository.flush();
        breakRepository.flush();
        for (ScheduleRules.DayRule rule : week.values()) {
            if (rule.start() == null) {
                continue;
            }
            scheduleRepository.save(ClinicSchedule.create(clinicId, rule.dayOfWeek(), rule.start(), rule.end()));
            for (LocalTime[] b : rule.breaks()) {
                breakRepository.save(ClinicBreak.create(clinicId, rule.dayOfWeek(), b[0], b[1]));
            }
        }
    }

    private ClinicScheduleView buildView(Long clinicId, String timezone) {
        Map<Integer, ClinicSchedule> schedules = new LinkedHashMap<>();
        for (ClinicSchedule s : scheduleRepository.findByClinicIdOrderByDayOfWeekAsc(clinicId)) {
            schedules.put(s.getDayOfWeek(), s);
        }
        Map<Integer, List<TimeRangeView>> breaks = new LinkedHashMap<>();
        for (ClinicBreak b : breakRepository.findByClinicIdOrderByDayOfWeekAscStartTimeAsc(clinicId)) {
            breaks.computeIfAbsent(b.getDayOfWeek(), k -> new ArrayList<>())
                    .add(new TimeRangeView(b.getStartTime(), b.getEndTime()));
        }
        List<ScheduleDayView> days = new ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            ClinicSchedule s = schedules.get(d);
            if (s == null) {
                days.add(new ScheduleDayView(d, false, null, null, List.of()));
            } else {
                days.add(new ScheduleDayView(d, true, s.getStartTime(), s.getEndTime(),
                        breaks.getOrDefault(d, List.of())));
            }
        }
        return new ClinicScheduleView(timezone, days);
    }

    private static ScheduleExceptionView toExceptionView(ClinicScheduleException e) {
        return new ScheduleExceptionView(e.getExceptionDate(), e.isClosed(), e.getStartTime(),
                e.getEndTime(), e.getReason());
    }

    static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Excepción vigente para una fecha, si la hay (usada por disponibilidad y horario público). */
    @Transactional(readOnly = true)
    public Optional<ClinicScheduleException> exceptionOn(Long clinicId, LocalDate date) {
        return exceptionRepository.findByClinicIdAndExceptionDate(clinicId, date);
    }
}
package com.dentalclinic.scheduling.application;

import java.time.Clock;
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
import com.dentalclinic.clinic.domain.Dentist;
import com.dentalclinic.clinic.infrastructure.repository.DentistRepository;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.identity.infrastructure.security.CurrentUser;
import com.dentalclinic.scheduling.api.dto.DayRequest;
import com.dentalclinic.scheduling.api.dto.DentistScheduleView;
import com.dentalclinic.scheduling.api.dto.ScheduleDayView;
import com.dentalclinic.scheduling.api.dto.ScheduleExceptionRequest;
import com.dentalclinic.scheduling.api.dto.ScheduleExceptionView;
import com.dentalclinic.scheduling.api.dto.TimeOffRequest;
import com.dentalclinic.scheduling.api.dto.TimeOffView;
import com.dentalclinic.scheduling.api.dto.TimeRangeView;
import com.dentalclinic.scheduling.api.dto.WeekRequest;
import com.dentalclinic.scheduling.domain.DentistBreak;
import com.dentalclinic.scheduling.domain.DentistSchedule;
import com.dentalclinic.scheduling.domain.DentistScheduleException;
import com.dentalclinic.scheduling.domain.DentistTimeOff;
import com.dentalclinic.scheduling.domain.ScheduleConflictException;
import com.dentalclinic.scheduling.domain.exception.InvalidScheduleException;
import com.dentalclinic.scheduling.infrastructure.repository.ClinicScheduleExceptionRepository;
import com.dentalclinic.scheduling.infrastructure.repository.ClinicScheduleRepository;
import com.dentalclinic.scheduling.infrastructure.repository.DentistBreakRepository;
import com.dentalclinic.scheduling.infrastructure.repository.DentistScheduleExceptionRepository;
import com.dentalclinic.scheduling.infrastructure.repository.DentistScheduleRepository;
import com.dentalclinic.scheduling.infrastructure.repository.DentistTimeOffRepository;

/**
 * Horario semanal de un dentista y sus excepciones/franquicias (RF-SC-03/04/06/07).
 *
 * <p>La pertenencia de fila se resuelve aquí y se traduce a 404: un DENTIST solo alcanza su propio
 * horario; OWNER escribe cualquiera; RECEPTIONIST solo lee (lo corta el filtro de matriz). El
 * horario del dentista debe quedar contenido en la ventana efectiva de la clínica → 409.
 */
@Service
public class DentistScheduleService {

    private final ClinicService clinicService;
    private final DentistRepository dentistRepository;
    private final ClinicScheduleRepository clinicScheduleRepository;
    private final ClinicScheduleExceptionRepository clinicExceptionRepository;
    private final DentistScheduleRepository scheduleRepository;
    private final DentistBreakRepository breakRepository;
    private final DentistScheduleExceptionRepository exceptionRepository;
    private final DentistTimeOffRepository timeOffRepository;
    private final Clock clock;

    public DentistScheduleService(ClinicService clinicService, DentistRepository dentistRepository,
            ClinicScheduleRepository clinicScheduleRepository,
            ClinicScheduleExceptionRepository clinicExceptionRepository,
            DentistScheduleRepository scheduleRepository, DentistBreakRepository breakRepository,
            DentistScheduleExceptionRepository exceptionRepository,
            DentistTimeOffRepository timeOffRepository, Clock clock) {
        this.clinicService = clinicService;
        this.dentistRepository = dentistRepository;
        this.clinicScheduleRepository = clinicScheduleRepository;
        this.clinicExceptionRepository = clinicExceptionRepository;
        this.scheduleRepository = scheduleRepository;
        this.breakRepository = breakRepository;
        this.exceptionRepository = exceptionRepository;
        this.timeOffRepository = timeOffRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DentistScheduleView get(Long dentistId) {
        Dentist dentist = resolve(dentistId);
        return buildView(dentist.getId(), clinicService.get().getTimezone());
    }

    @Transactional
    public DentistScheduleView replaceWeek(Long dentistId, WeekRequest request) {
        Dentist dentist = resolve(dentistId);
        Clinic clinic = clinicService.get();
        SortedMap<Integer, ScheduleRules.DayRule> week = ScheduleRules.validateWeek(request.days());
        for (ScheduleRules.DayRule rule : week.values()) {
            requireContained(clinic.getId(), rule);
        }
        Long id = dentist.getId();
        scheduleRepository.deleteByDentistId(id);
        breakRepository.deleteByDentistId(id);
        scheduleRepository.flush();
        breakRepository.flush();
        for (ScheduleRules.DayRule rule : week.values()) {
            if (rule.start() == null) {
                continue;
            }
            scheduleRepository.save(DentistSchedule.create(id, rule.dayOfWeek(), rule.start(), rule.end()));
            for (LocalTime[] b : rule.breaks()) {
                breakRepository.save(DentistBreak.create(id, rule.dayOfWeek(), b[0], b[1]));
            }
        }
        return buildView(id, clinic.getTimezone());
    }

    @Transactional
    public DentistScheduleView replaceDay(Long dentistId, int dayOfWeek, DayRequest request) {
        Dentist dentist = resolve(dentistId);
        Clinic clinic = clinicService.get();
        ScheduleRules.DayRule rule = ScheduleRules.validateDay(dayOfWeek, request.open(), request.startTime(),
                request.endTime(), request.breaks());
        requireContained(clinic.getId(), rule);
        Long id = dentist.getId();
        scheduleRepository.deleteByDentistIdAndDayOfWeek(id, dayOfWeek);
        breakRepository.deleteByDentistIdAndDayOfWeek(id, dayOfWeek);
        scheduleRepository.flush();
        breakRepository.flush();
        if (rule.start() != null) {
            scheduleRepository.save(DentistSchedule.create(id, dayOfWeek, rule.start(), rule.end()));
            for (LocalTime[] b : rule.breaks()) {
                breakRepository.save(DentistBreak.create(id, dayOfWeek, b[0], b[1]));
            }
        }
        return buildView(id, clinic.getTimezone());
    }

    @Transactional(readOnly = true)
    public List<ScheduleExceptionView> listExceptions(Long dentistId) {
        Dentist dentist = resolve(dentistId);
        return exceptionRepository.findByDentistIdOrderByExceptionDateAsc(dentist.getId()).stream()
                .map(DentistScheduleService::toExceptionView)
                .toList();
    }

    @Transactional
    public ScheduleExceptionView putException(Long dentistId, LocalDate date,
            ScheduleExceptionRequest request) {
        Dentist dentist = resolve(dentistId);
        Clinic clinic = clinicService.get();
        LocalTime start = null;
        LocalTime end = null;
        if (!request.closed()) {
            start = request.startTime();
            end = request.endTime();
            if (start == null || end == null || !start.isBefore(end)) {
                throw new InvalidScheduleException("Una excepción abierta debe tener inicio anterior al fin.");
            }
            LocalTime[] win = effectiveClinicWindow(clinic.getId(), date);
            if (win == null) {
                throw new ScheduleConflictException(
                        "No se puede abrir el dentista una fecha en la que la clínica está cerrada.");
            }
            if (start.isBefore(win[0]) || end.isAfter(win[1])) {
                throw new ScheduleConflictException(
                        "El horario del dentista debe quedar dentro de la ventana de la clínica.");
            }
        }
        Long id = dentist.getId();
        exceptionRepository.deleteByDentistIdAndExceptionDate(id, date);
        exceptionRepository.flush();
        DentistScheduleException saved = exceptionRepository.save(
                DentistScheduleException.create(id, date, request.closed(), start, end,
                        ClinicScheduleService.normalize(request.reason())));
        return toExceptionView(saved);
    }

    @Transactional
    public void deleteException(Long dentistId, LocalDate date) {
        exceptionRepository.deleteByDentistIdAndExceptionDate(resolve(dentistId).getId(), date);
    }

    @Transactional(readOnly = true)
    public List<TimeOffView> listTimeOff(Long dentistId) {
        Dentist dentist = resolve(dentistId);
        return timeOffRepository.findByDentistIdOrderByStartDateDesc(dentist.getId()).stream()
                .map(DentistScheduleService::toTimeOffView)
                .toList();
    }

    @Transactional
    public TimeOffView createTimeOff(Long dentistId, TimeOffRequest request) {
        Dentist dentist = resolve(dentistId);
        TimeOffData data = validateTimeOff(request);
        DentistTimeOff saved = timeOffRepository.save(DentistTimeOff.create(dentist.getId(),
                data.startDate(), data.endDate(), data.startTime(), data.endTime(), data.reason()));
        return toTimeOffView(saved);
    }

    @Transactional
    public TimeOffView updateTimeOff(Long dentistId, Long timeOffId, TimeOffRequest request) {
        Dentist dentist = resolve(dentistId);
        DentistTimeOff timeOff = timeOffRepository.findByIdAndDentistId(timeOffId, dentist.getId())
                .orElseThrow(NotFoundException::new);
        TimeOffData data = validateTimeOff(request);
        timeOff.update(data.startDate(), data.endDate(), data.startTime(), data.endTime(), data.reason());
        return toTimeOffView(timeOff);
    }

    @Transactional
    public void deleteTimeOff(Long dentistId, Long timeOffId) {
        Dentist dentist = resolve(dentistId);
        DentistTimeOff timeOff = timeOffRepository.findByIdAndDentistId(timeOffId, dentist.getId())
                .orElseThrow(NotFoundException::new);
        timeOffRepository.delete(timeOff);
    }

    /**
     * Resuelve el dentista del path. Un DENTIST solo puede operar sobre sí mismo: un id ajeno es
     * 404, no 403 (§8.2, coherencia con la gestión de pacientes).
     */
    private Dentist resolve(Long dentistId) {
        Dentist dentist = dentistRepository.findById(dentistId).orElseThrow(NotFoundException::new);
        AuthenticatedUser principal = CurrentUser.require();
        if (principal.role() == Role.DENTIST) {
            Dentist self = dentistRepository.findByUserId(principal.userId())
                    .orElseThrow(NotFoundException::new);
            if (!self.getId().equals(dentistId)) {
                throw new NotFoundException();
            }
        }
        return dentist;
    }

    private void requireContained(Long clinicId, ScheduleRules.DayRule rule) {
        if (rule.start() == null) {
            return;
        }
        LocalTime[] win = clinicWeeklyWindow(clinicId, rule.dayOfWeek());
        if (win == null) {
            throw new ScheduleConflictException(
                    "No se puede abrir el dentista un día en el que la clínica está cerrada.");
        }
        if (rule.start().isBefore(win[0]) || rule.end().isAfter(win[1])) {
            throw new ScheduleConflictException(
                    "El horario del dentista debe quedar dentro de la ventana de la clínica.");
        }
    }

    private LocalTime[] clinicWeeklyWindow(Long clinicId, int dayOfWeek) {
        return clinicScheduleRepository.findByClinicIdAndDayOfWeek(clinicId, dayOfWeek)
                .map(s -> new LocalTime[] {s.getStartTime(), s.getEndTime()})
                .orElse(null);
    }

    /** Ventana efectiva de la clínica en una fecha: excepción si la hay, si no la franja semanal. */
    private LocalTime[] effectiveClinicWindow(Long clinicId, LocalDate date) {
        Optional<com.dentalclinic.scheduling.domain.ClinicScheduleException> exception =
                clinicExceptionRepository.findByClinicIdAndExceptionDate(clinicId, date);
        if (exception.isPresent()) {
            com.dentalclinic.scheduling.domain.ClinicScheduleException e = exception.get();
            if (e.isClosed()) {
                return null;
            }
            return new LocalTime[] {e.getStartTime(), e.getEndTime()};
        }
        int dayOfWeek = date.getDayOfWeek().getValue();
        return clinicWeeklyWindow(clinicId, dayOfWeek);
    }

    private DentistScheduleView buildView(Long dentistId, String timezone) {
        Map<Integer, DentistSchedule> schedules = new LinkedHashMap<>();
        for (DentistSchedule s : scheduleRepository.findByDentistIdOrderByDayOfWeekAsc(dentistId)) {
            schedules.put(s.getDayOfWeek(), s);
        }
        Map<Integer, List<TimeRangeView>> breaks = new LinkedHashMap<>();
        for (DentistBreak b : breakRepository.findByDentistIdOrderByDayOfWeekAscStartTimeAsc(dentistId)) {
            breaks.computeIfAbsent(b.getDayOfWeek(), k -> new ArrayList<>())
                    .add(new TimeRangeView(b.getStartTime(), b.getEndTime()));
        }
        List<ScheduleDayView> days = new ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            DentistSchedule s = schedules.get(d);
            if (s == null) {
                days.add(new ScheduleDayView(d, false, null, null, List.of()));
            } else {
                days.add(new ScheduleDayView(d, true, s.getStartTime(), s.getEndTime(),
                        breaks.getOrDefault(d, List.of())));
            }
        }
        return new DentistScheduleView(dentistId, timezone, days);
    }

    private static ScheduleExceptionView toExceptionView(DentistScheduleException e) {
        return new ScheduleExceptionView(e.getExceptionDate(), e.isClosed(), e.getStartTime(),
                e.getEndTime(), e.getReason());
    }

    private static TimeOffView toTimeOffView(DentistTimeOff t) {
        return new TimeOffView(t.getId(), t.getStartDate(), t.getEndDate(), t.isAllDay(),
                t.getStartTime(), t.getEndTime(), t.getReason());
    }

    private static TimeOffData validateTimeOff(TimeOffRequest request) {
        LocalDate startDate = request.startDate();
        LocalDate endDate = request.endDate();
        if (startDate == null || endDate == null) {
            throw new InvalidScheduleException("Las fechas de inicio y fin son obligatorias.");
        }
        if (startDate.isAfter(endDate)) {
            throw new InvalidScheduleException("La fecha de inicio no puede ser posterior a la de fin.");
        }
        LocalTime startTime = request.startTime();
        LocalTime endTime = request.endTime();
        if ((startTime == null) != (endTime == null)) {
            throw new InvalidScheduleException(
                    "Una franquicia parcial requiere hora de inicio y de fin; para días completos, omite ambas.");
        }
        if (startTime != null) {
            if (!startTime.isBefore(endTime)) {
                throw new InvalidScheduleException("La hora de inicio debe ser anterior a la de fin.");
            }
            if (!startDate.equals(endDate)) {
                throw new InvalidScheduleException(
                        "Una franquicia con horas cubre un único día; para varios días usa días completos.");
            }
        }
        return new TimeOffData(startDate, endDate, startTime, endTime,
                ClinicScheduleService.normalize(request.reason()));
    }

    private record TimeOffData(LocalDate startDate, LocalDate endDate, LocalTime startTime,
                               LocalTime endTime, String reason) {
    }
}
package com.dentalclinic.scheduling.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.clinic.application.ClinicService;
import com.dentalclinic.clinic.domain.Clinic;
import com.dentalclinic.clinic.infrastructure.repository.DentistRepository;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.scheduling.api.dto.AvailabilityResponse;
import com.dentalclinic.scheduling.api.dto.AvailabilitySlot;
import com.dentalclinic.scheduling.domain.ClinicBreak;
import com.dentalclinic.scheduling.domain.ClinicSchedule;
import com.dentalclinic.scheduling.domain.ClinicScheduleException;
import com.dentalclinic.scheduling.domain.DentistBreak;
import com.dentalclinic.scheduling.domain.DentistSchedule;
import com.dentalclinic.scheduling.domain.DentistScheduleException;
import com.dentalclinic.scheduling.domain.DentistTimeOff;
import com.dentalclinic.scheduling.domain.ScheduleConflictException;
import com.dentalclinic.scheduling.domain.exception.InvalidScheduleException;
import com.dentalclinic.scheduling.infrastructure.repository.ClinicBreakRepository;
import com.dentalclinic.scheduling.infrastructure.repository.ClinicScheduleExceptionRepository;
import com.dentalclinic.scheduling.infrastructure.repository.ClinicScheduleRepository;
import com.dentalclinic.scheduling.infrastructure.repository.DentistBreakRepository;
import com.dentalclinic.scheduling.infrastructure.repository.DentistScheduleExceptionRepository;
import com.dentalclinic.scheduling.infrastructure.repository.DentistScheduleRepository;
import com.dentalclinic.scheduling.infrastructure.repository.DentistTimeOffRepository;

/**
 * Motor de disponibilidad (RF-AV-01..12). Calcula <strong>sin persistir nada</strong> (A-06).
 *
 * <p>Por fecha: excepción &gt; ventana semanal, para clínica y dentista; se intersecan; se restan
 * breaks semanales y franquicias; se generan comienzos cada 15 min en hora local; se exige que
 * {@code [inicio, inicio+duración)} encaje; se aplica la política DST conservadora, el lead time y
 * la ocupación real de citas {@code SCHEDULED} del dentista (A-14).
 *
 * <p>La ocupación entra por {@link AppointmentOccupancyReader} (dependency inversion, §6.3/§6.4):
 * este módulo no depende del módulo de citas. {@link #assertBookable} aplica las mismas reglas
 * geométricas a un intervalo concreto para reservar/reprogramar sin duplicar la lógica (A-20).
 */
@Service
public class AvailabilityService {

    private final ClinicService clinicService;
    private final DentistRepository dentistRepository;
    private final ClinicScheduleRepository clinicScheduleRepository;
    private final ClinicBreakRepository clinicBreakRepository;
    private final ClinicScheduleExceptionRepository clinicExceptionRepository;
    private final DentistScheduleRepository dentistScheduleRepository;
    private final DentistBreakRepository dentistBreakRepository;
    private final DentistScheduleExceptionRepository dentistExceptionRepository;
    private final DentistTimeOffRepository timeOffRepository;
    private final AppointmentOccupancyReader occupancyReader;
    private final Clock clock;

    public AvailabilityService(ClinicService clinicService, DentistRepository dentistRepository,
            ClinicScheduleRepository clinicScheduleRepository, ClinicBreakRepository clinicBreakRepository,
            ClinicScheduleExceptionRepository clinicExceptionRepository,
            DentistScheduleRepository dentistScheduleRepository, DentistBreakRepository dentistBreakRepository,
            DentistScheduleExceptionRepository dentistExceptionRepository,
            DentistTimeOffRepository timeOffRepository, AppointmentOccupancyReader occupancyReader,
            Clock clock) {
        this.clinicService = clinicService;
        this.dentistRepository = dentistRepository;
        this.clinicScheduleRepository = clinicScheduleRepository;
        this.clinicBreakRepository = clinicBreakRepository;
        this.clinicExceptionRepository = clinicExceptionRepository;
        this.dentistScheduleRepository = dentistScheduleRepository;
        this.dentistBreakRepository = dentistBreakRepository;
        this.dentistExceptionRepository = dentistExceptionRepository;
        this.timeOffRepository = timeOffRepository;
        this.occupancyReader = occupancyReader;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AvailabilityResponse compute(Long dentistId, LocalDate from, LocalDate to, int durationMinutes) {
        if (durationMinutes <= 0) {
            throw new InvalidScheduleException("La duración debe ser mayor que cero.");
        }
        dentistRepository.findById(dentistId)
                .orElseThrow(() -> new NotFoundException("No encontramos el dentista indicado."));
        Clinic clinic = clinicService.get();
        Long clinicId = clinic.getId();
        ZoneId zone = ZoneId.of(clinic.getTimezone());
        Instant now = clock.instant();
        LocalDate today = now.atZone(zone).toLocalDate();

        LocalDate rangeEnd = to != null ? to : from;
        if (rangeEnd.isBefore(from)) {
            throw new InvalidScheduleException("El rango de fechas es inválido.");
        }
        LocalDate capEnd = today.plusDays(clinic.getMaxAdvanceDays());
        LocalDate effectiveEnd = rangeEnd.isAfter(capEnd) ? capEnd : rangeEnd;
        LocalDate effectiveStart = from.isBefore(today) ? today : from;

        Map<Integer, ClinicSchedule> clinicWeekly = new HashMap<>();
        for (ClinicSchedule s : clinicScheduleRepository.findByClinicIdOrderByDayOfWeekAsc(clinicId)) {
            clinicWeekly.put(s.getDayOfWeek(), s);
        }
        Map<Integer, List<LocalTime[]>> clinicBreaks = new HashMap<>();
        for (ClinicBreak b : clinicBreakRepository
                .findByClinicIdOrderByDayOfWeekAscStartTimeAsc(clinicId)) {
            clinicBreaks.computeIfAbsent(b.getDayOfWeek(), k -> new ArrayList<>())
                    .add(new LocalTime[] {b.getStartTime(), b.getEndTime()});
        }
        Map<LocalDate, ClinicScheduleException> clinicExceptions = new HashMap<>();
        for (ClinicScheduleException e : clinicExceptionRepository
                .findByClinicIdOrderByExceptionDateAsc(clinicId)) {
            clinicExceptions.put(e.getExceptionDate(), e);
        }

        Map<Integer, DentistSchedule> dentistWeekly = new HashMap<>();
        for (DentistSchedule s : dentistScheduleRepository.findByDentistIdOrderByDayOfWeekAsc(dentistId)) {
            dentistWeekly.put(s.getDayOfWeek(), s);
        }
        Map<Integer, List<LocalTime[]>> dentistBreaks = new HashMap<>();
        for (DentistBreak b : dentistBreakRepository
                .findByDentistIdOrderByDayOfWeekAscStartTimeAsc(dentistId)) {
            dentistBreaks.computeIfAbsent(b.getDayOfWeek(), k -> new ArrayList<>())
                    .add(new LocalTime[] {b.getStartTime(), b.getEndTime()});
        }
        Map<LocalDate, DentistScheduleException> dentistExceptions = new HashMap<>();
        for (DentistScheduleException e : dentistExceptionRepository
                .findByDentistIdOrderByExceptionDateAsc(dentistId)) {
            dentistExceptions.put(e.getExceptionDate(), e);
        }

        Set<LocalDate> allDayOff = new HashSet<>();
        Map<LocalDate, List<LocalTime[]>> partialOff = new HashMap<>();
        for (DentistTimeOff t : timeOffRepository.findByDentistIdOrderByStartDateDesc(dentistId)) {
            if (t.isAllDay()) {
                LocalDate d = t.getStartDate();
                while (!d.isAfter(t.getEndDate())) {
                    allDayOff.add(d);
                    d = d.plusDays(1);
                }
            } else {
                partialOff.computeIfAbsent(t.getStartDate(), k -> new ArrayList<>())
                        .add(new LocalTime[] {t.getStartTime(), t.getEndTime()});
            }
        }

        // Ocupación real de citas SCHEDULED en el rango, en una sola consulta (A-14). El uso de
        // instantes de día completo (inicio de effectiveStart, día siguiente a effectiveEnd) cubre
        // cualquier slot DST del rango sin acotar por hora local.
        List<AppointmentOccupancyReader.OccupiedInterval> occupied =
                occupancyReader.occupiedIntervalsInRange(dentistId,
                        effectiveStart.atStartOfDay(zone).toInstant(),
                        effectiveEnd.plusDays(1).atStartOfDay(zone).toInstant());

        Instant minStart = now.plus(Duration.ofHours(clinic.getMinBookingLeadTimeHours()));
        List<AvailabilitySlot> slots = new ArrayList<>();

        for (LocalDate date = effectiveStart; !date.isAfter(effectiveEnd); date = date.plusDays(1)) {
            int dayOfWeek = date.getDayOfWeek().getValue();
            LocalTime[] clinicWindow = resolveWindow(clinicExceptions.get(date),
                    weeklyClinicWindow(clinicWeekly.get(dayOfWeek)));
            if (clinicWindow == null) {
                continue;
            }
            LocalTime[] dentistWindow = resolveWindow(dentistExceptions.get(date),
                    weeklyDentistWindow(dentistWeekly.get(dayOfWeek)));
            if (dentistWindow == null) {
                continue;
            }
            LocalTime windowStart = max(clinicWindow[0], dentistWindow[0]);
            LocalTime windowEnd = min(clinicWindow[1], dentistWindow[1]);
            if (!windowStart.isBefore(windowEnd)) {
                continue;
            }
            List<LocalTime[]> free = freeSegmentsForDate(windowStart, windowEnd, date, dayOfWeek,
                    clinicBreaks, dentistBreaks, partialOff, allDayOff);

            for (LocalTime[] segment : free) {
                LocalTime start = ceilToGrid(segment[0]);
                while (!start.isAfter(segment[1])) {
                    LocalTime end = start.plusMinutes(durationMinutes);
                    if (end.isBefore(start) || end.isAfter(segment[1])) {
                        break;
                    }
                    Optional<Instant> resolved = resolveStart(date, start, durationMinutes, zone);
                    if (resolved.isPresent() && !resolved.get().isBefore(minStart)
                            && !overlapsOccupancy(occupied, resolved.get(),
                                    resolved.get().plus(Duration.ofMinutes(durationMinutes)))) {
                        slots.add(new AvailabilitySlot(date, start, end, resolved.get(),
                                resolved.get().plus(Duration.ofMinutes(durationMinutes))));
                    }
                    start = start.plusMinutes(15);
                }
            }
        }

        slots.sort(Comparator.comparing(AvailabilitySlot::start));
        return new AvailabilityResponse(dentistId, durationMinutes, clinic.getTimezone(), slots);
    }

    /**
     * Comprueba que un intervalo concreto {@code [startAt, endAt)} es reservable (A-20): dentro de
     * la ventana horaria (contando excepciones, breaks y franquicias) y sin solapar una cita
     * {@code SCHEDULED} del dentista. No valida rol, lead time ni anticipación: eso es de la capa de
     * citas al decidir el "quién" y el "cuándo".
     *
     * @param excludeAppointmentId cita a ignorar (reprogramación de la propia cita), o {@code null}
     * @throws InvalidScheduleException si el intervalo es inválido o cruza la medianoche
     * @throws ScheduleConflictException si cae fuera del horario o ya está ocupado
     * @throws NotFoundException si el dentista no existe
     */
    @Transactional(readOnly = true)
    public void assertBookable(Long dentistId, Instant startAt, Instant endAt,
                               Long excludeAppointmentId) {
        if (startAt == null || endAt == null) {
            throw new InvalidScheduleException("Los límites de la cita (inicio y fin) son obligatorios.");
        }
        if (!startAt.isBefore(endAt)) {
            throw new InvalidScheduleException("La cita debe terminar después de empezar (end_at > start_at).");
        }
        dentistRepository.findById(dentistId)
                .orElseThrow(() -> new NotFoundException("No encontramos el dentista indicado."));
        Clinic clinic = clinicService.get();
        Long clinicId = clinic.getId();
        ZoneId zone = ZoneId.of(clinic.getTimezone());

        LocalDateTime startLocal = startAt.atZone(zone).toLocalDateTime();
        LocalDateTime endLocal = endAt.atZone(zone).toLocalDateTime();
        if (!startLocal.toLocalDate().equals(endLocal.toLocalDate())) {
            throw new InvalidScheduleException(
                    "La cita no puede cruzar la medianoche; elige un horario dentro del mismo día.");
        }
        LocalDate date = startLocal.toLocalDate();

        Map<Integer, ClinicSchedule> clinicWeekly = new HashMap<>();
        for (ClinicSchedule s : clinicScheduleRepository.findByClinicIdOrderByDayOfWeekAsc(clinicId)) {
            clinicWeekly.put(s.getDayOfWeek(), s);
        }
        Map<Integer, List<LocalTime[]>> clinicBreaks = new HashMap<>();
        for (ClinicBreak b : clinicBreakRepository
                .findByClinicIdOrderByDayOfWeekAscStartTimeAsc(clinicId)) {
            clinicBreaks.computeIfAbsent(b.getDayOfWeek(), k -> new ArrayList<>())
                    .add(new LocalTime[] {b.getStartTime(), b.getEndTime()});
        }
        Map<LocalDate, ClinicScheduleException> clinicExceptions = new HashMap<>();
        for (ClinicScheduleException e : clinicExceptionRepository
                .findByClinicIdOrderByExceptionDateAsc(clinicId)) {
            clinicExceptions.put(e.getExceptionDate(), e);
        }

        Map<Integer, DentistSchedule> dentistWeekly = new HashMap<>();
        for (DentistSchedule s : dentistScheduleRepository.findByDentistIdOrderByDayOfWeekAsc(dentistId)) {
            dentistWeekly.put(s.getDayOfWeek(), s);
        }
        Map<Integer, List<LocalTime[]>> dentistBreaks = new HashMap<>();
        for (DentistBreak b : dentistBreakRepository
                .findByDentistIdOrderByDayOfWeekAscStartTimeAsc(dentistId)) {
            dentistBreaks.computeIfAbsent(b.getDayOfWeek(), k -> new ArrayList<>())
                    .add(new LocalTime[] {b.getStartTime(), b.getEndTime()});
        }
        Map<LocalDate, DentistScheduleException> dentistExceptions = new HashMap<>();
        for (DentistScheduleException e : dentistExceptionRepository
                .findByDentistIdOrderByExceptionDateAsc(dentistId)) {
            dentistExceptions.put(e.getExceptionDate(), e);
        }

        Set<LocalDate> allDayOff = new HashSet<>();
        Map<LocalDate, List<LocalTime[]>> partialOff = new HashMap<>();
        for (DentistTimeOff t : timeOffRepository.findByDentistIdOrderByStartDateDesc(dentistId)) {
            if (t.isAllDay()) {
                LocalDate d = t.getStartDate();
                while (!d.isAfter(t.getEndDate())) {
                    allDayOff.add(d);
                    d = d.plusDays(1);
                }
            } else {
                partialOff.computeIfAbsent(t.getStartDate(), k -> new ArrayList<>())
                        .add(new LocalTime[] {t.getStartTime(), t.getEndTime()});
            }
        }

        LocalTime[] clinicWindow = resolveWindow(clinicExceptions.get(date),
                weeklyClinicWindow(clinicWeekly.get(date.getDayOfWeek().getValue())));
        LocalTime[] dentistWindow = resolveWindow(dentistExceptions.get(date),
                weeklyDentistWindow(dentistWeekly.get(date.getDayOfWeek().getValue())));
        List<LocalTime[]> free = clinicWindow != null && dentistWindow != null
                ? freeSegmentsForDate(max(clinicWindow[0], dentistWindow[0]),
                        min(clinicWindow[1], dentistWindow[1]), date,
                        date.getDayOfWeek().getValue(), clinicBreaks, dentistBreaks, partialOff,
                        allDayOff)
                : List.of();
        if (!isContained(startLocal.toLocalTime(), endLocal.toLocalTime(), free)) {
            throw new ScheduleConflictException(
                    "El horario elegido cae fuera del horario de atención o en un descanso; selecciona otro.");
        }
        if (!occupancyReader.overlapping(dentistId, startAt, endAt, excludeAppointmentId).isEmpty()) {
            throw new ScheduleConflictException("Ese horario ya fue tomado. Selecciona otro horario.");
        }
    }

    private static boolean isContained(LocalTime start, LocalTime end, List<LocalTime[]> free) {
        for (LocalTime[] segment : free) {
            if (!segment[0].isAfter(start) && !end.isAfter(segment[1])) {
                return true;
            }
        }
        return false;
    }

    /** Segmentos libres de un día tras intersecar ventanas y restar breaks y franquicias. */
    private static List<LocalTime[]> freeSegmentsForDate(LocalTime windowStart, LocalTime windowEnd,
            LocalDate date, int dayOfWeek, Map<Integer, List<LocalTime[]>> clinicBreaks,
            Map<Integer, List<LocalTime[]>> dentistBreaks,
            Map<LocalDate, List<LocalTime[]>> partialOff, Set<LocalDate> allDayOff) {
        if (allDayOff.contains(date)) {
            return List.of();
        }
        List<LocalTime[]> free = new ArrayList<>();
        free.add(new LocalTime[] {windowStart, windowEnd});
        free = subtract(free, clinicBreaks.getOrDefault(dayOfWeek, List.of()));
        free = subtract(free, dentistBreaks.getOrDefault(dayOfWeek, List.of()));
        free = subtract(free, partialOff.getOrDefault(date, List.of()));
        return free;
    }

    private static boolean overlapsOccupancy(
            List<AppointmentOccupancyReader.OccupiedInterval> occupied, Instant start, Instant end) {
        for (AppointmentOccupancyReader.OccupiedInterval o : occupied) {
            if (!o.end().isAfter(start) || !o.start().isBefore(end)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static LocalTime[] resolveWindow(ClinicScheduleException exception, LocalTime[] weekly) {
        if (exception != null) {
            return exception.isClosed() ? null
                    : new LocalTime[] {exception.getStartTime(), exception.getEndTime()};
        }
        return weekly;
    }

    private static LocalTime[] resolveWindow(DentistScheduleException exception, LocalTime[] weekly) {
        if (exception != null) {
            return exception.isClosed() ? null
                    : new LocalTime[] {exception.getStartTime(), exception.getEndTime()};
        }
        return weekly;
    }

    private static LocalTime[] weeklyClinicWindow(ClinicSchedule s) {
        return s == null ? null : new LocalTime[] {s.getStartTime(), s.getEndTime()};
    }

    private static LocalTime[] weeklyDentistWindow(DentistSchedule s) {
        return s == null ? null : new LocalTime[] {s.getStartTime(), s.getEndTime()};
    }

    private static List<LocalTime[]> subtract(List<LocalTime[]> free, List<LocalTime[]> removed) {
        List<LocalTime[]> current = free;
        for (LocalTime[] rem : removed) {
            List<LocalTime[]> next = new ArrayList<>();
            for (LocalTime[] piece : current) {
                next.addAll(carve(piece, rem));
            }
            current = next;
        }
        return current;
    }

    private static List<LocalTime[]> carve(LocalTime[] piece, LocalTime[] rem) {
        LocalTime ps = piece[0];
        LocalTime pe = piece[1];
        LocalTime rs = rem[0];
        LocalTime re = rem[1];
        if (!re.isAfter(ps) || !rs.isBefore(pe)) {
            return java.util.Collections.singletonList(piece);
        }
        List<LocalTime[]> result = new ArrayList<>();
        if (rs.isAfter(ps)) {
            result.add(new LocalTime[] {ps, rs});
        }
        if (re.isBefore(pe)) {
            result.add(new LocalTime[] {re, pe});
        }
        return result;
    }

    private static LocalTime ceilToGrid(LocalTime time) {
        int remainder = time.getMinute() % 15;
        if (remainder == 0 && time.getSecond() == 0 && time.getNano() == 0) {
            return time;
        }
        return time.plusMinutes(15 - remainder).withSecond(0).withNano(0);
    }

    private static LocalTime max(LocalTime a, LocalTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalTime min(LocalTime a, LocalTime b) {
        return a.isBefore(b) ? a : b;
    }

    /**
     * Resuelve hora local → instante con política conservadora: solo acepta si inicio y fin tienen
     * una única interpretación y la duración real coincide con la solicitada (evita gaps y overlaps
     * DST). Nunca usa la zona por defecto de la JVM.
     */
    static Optional<Instant> resolveStart(LocalDate date, LocalTime start, int durationMinutes,
                                          ZoneId zone) {
        ZoneRules rules = zone.getRules();
        LocalDateTime startLocal = LocalDateTime.of(date, start);
        List<ZoneOffset> startOffsets = rules.getValidOffsets(startLocal);
        if (startOffsets.size() != 1) {
            return Optional.empty();
        }
        LocalDateTime endLocal = startLocal.plusMinutes(durationMinutes);
        List<ZoneOffset> endOffsets = rules.getValidOffsets(endLocal);
        if (endOffsets.size() != 1) {
            return Optional.empty();
        }
        Instant startInstant = startLocal.toInstant(startOffsets.get(0));
        Instant endInstant = endLocal.toInstant(endOffsets.get(0));
        if (Duration.between(startInstant, endInstant).toMinutes() != durationMinutes) {
            return Optional.empty();
        }
        return Optional.of(startInstant);
    }
}
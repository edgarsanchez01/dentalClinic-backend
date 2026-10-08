package com.dentalclinic.scheduling.application;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

import com.dentalclinic.scheduling.api.dto.TimeRangeRequest;
import com.dentalclinic.scheduling.api.dto.WeekDayRequest;
import com.dentalclinic.scheduling.domain.ScheduleConflictException;
import com.dentalclinic.scheduling.domain.exception.InvalidScheduleException;

/**
 * Validación de horarios, común a clínica y dentista.
 *
 * <p>Errores de forma (franja inválida, break fuera de la franja, día duplicado/faltante) son
 * {@link InvalidScheduleException} (400); el solapamiento de breaks es
 * {@link ScheduleConflictException} (409), en línea con la decisión de Fase 5.
 */
final class ScheduleRules {

    private ScheduleRules() {
    }

    /** Un día ya validado, con la franja y los breaks contenidos en ella. */
    record DayRule(int dayOfWeek, LocalTime start, LocalTime end, List<LocalTime[]> breaks) {
    }

    static SortedMap<Integer, DayRule> validateWeek(List<WeekDayRequest> days) {
        if (days == null) {
            throw new InvalidScheduleException("La semana debe incluir los siete días.");
        }
        TreeMap<Integer, DayRule> result = new TreeMap<>();
        for (WeekDayRequest day : days) {
            int d = day.dayOfWeek();
            if (d < 1 || d > 7) {
                throw new InvalidScheduleException("El día debe estar entre 1 (lunes) y 7 (domingo).");
            }
            if (result.containsKey(d)) {
                throw new InvalidScheduleException("El día " + d + " aparece más de una vez.");
            }
            result.put(d, validateDay(d, day.open(), day.startTime(), day.endTime(), day.breaks()));
        }
        for (int d = 1; d <= 7; d++) {
            if (!result.containsKey(d)) {
                throw new InvalidScheduleException("Falta el día " + d + " en la semana.");
            }
        }
        return result;
    }

    static DayRule validateDay(int dayOfWeek, boolean open, LocalTime start, LocalTime end,
                               List<TimeRangeRequest> breaks) {
        if (dayOfWeek < 1 || dayOfWeek > 7) {
            throw new InvalidScheduleException("El día debe estar entre 1 (lunes) y 7 (domingo).");
        }
        if (!open) {
            return new DayRule(dayOfWeek, null, null, List.of());
        }
        if (start == null || end == null || !start.isBefore(end)) {
            throw new InvalidScheduleException("La franja de un día abierto debe tener inicio anterior al fin.");
        }
        List<LocalTime[]> normalized = normalizeBreaks(breaks, start, end);
        return new DayRule(dayOfWeek, start, end, normalized);
    }

    private static List<LocalTime[]> normalizeBreaks(List<TimeRangeRequest> breaks, LocalTime winStart,
                                                     LocalTime winEnd) {
        List<LocalTime[]> result = new ArrayList<>();
        if (breaks == null) {
            return result;
        }
        for (TimeRangeRequest b : breaks) {
            LocalTime s = b.startTime();
            LocalTime e = b.endTime();
            if (s == null || e == null || !s.isBefore(e)) {
                throw new InvalidScheduleException("Cada break debe tener inicio anterior al fin.");
            }
            if (s.isBefore(winStart) || e.isAfter(winEnd)) {
                throw new InvalidScheduleException("Los breaks deben quedar dentro de la franja del día.");
            }
            result.add(new LocalTime[] {s, e});
        }
        result.sort(Comparator.comparing(x -> x[0]));
        for (int i = 1; i < result.size(); i++) {
            if (result.get(i)[0].isBefore(result.get(i - 1)[1])) {
                throw new ScheduleConflictException("Los breaks de un día no pueden solaparse.");
            }
        }
        return result;
    }
}
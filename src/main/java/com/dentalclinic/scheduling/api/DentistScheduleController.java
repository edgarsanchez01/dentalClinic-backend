package com.dentalclinic.scheduling.api;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dentalclinic.scheduling.api.dto.DayRequest;
import com.dentalclinic.scheduling.api.dto.DentistScheduleView;
import com.dentalclinic.scheduling.api.dto.ScheduleExceptionRequest;
import com.dentalclinic.scheduling.api.dto.ScheduleExceptionView;
import com.dentalclinic.scheduling.api.dto.TimeOffRequest;
import com.dentalclinic.scheduling.api.dto.TimeOffView;
import com.dentalclinic.scheduling.api.dto.WeekRequest;
import com.dentalclinic.scheduling.application.DentistScheduleService;

import jakarta.validation.Valid;

/**
 * Horario semanal, excepciones y franquicias de un dentista (RF-SC-03/04/06/07).
 *
 * <p>El {@code {dentistId}} es el de {@code Dentist}. La pertenencia de fila (un DENTIST solo
 * alcanza el suyo) se resuelve en el servicio y se traduce a 404.
 */
@RestController
@RequestMapping("/api/schedules/dentist")
public class DentistScheduleController {

    private final DentistScheduleService service;

    public DentistScheduleController(DentistScheduleService service) {
        this.service = service;
    }

    @GetMapping("/{dentistId}")
    public DentistScheduleView get(@PathVariable("dentistId") Long dentistId) {
        return service.get(dentistId);
    }

    @PutMapping("/{dentistId}")
    public DentistScheduleView replaceWeek(@PathVariable("dentistId") Long dentistId,
                                           @Valid @RequestBody WeekRequest request) {
        return service.replaceWeek(dentistId, request);
    }

    @PutMapping("/{dentistId}/day/{day:[1-7]}")
    public DentistScheduleView replaceDay(@PathVariable("dentistId") Long dentistId,
                                          @PathVariable("day") int day,
                                          @Valid @RequestBody DayRequest request) {
        return service.replaceDay(dentistId, day, request);
    }

    @GetMapping("/{dentistId}/exceptions")
    public List<ScheduleExceptionView> listExceptions(@PathVariable("dentistId") Long dentistId) {
        return service.listExceptions(dentistId);
    }

    @PutMapping("/{dentistId}/exceptions/{date}")
    public ScheduleExceptionView putException(@PathVariable("dentistId") Long dentistId,
                                              @PathVariable("date")
                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                              @Valid @RequestBody ScheduleExceptionRequest request) {
        return service.putException(dentistId, date, request);
    }

    @DeleteMapping("/{dentistId}/exceptions/{date}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteException(@PathVariable("dentistId") Long dentistId,
                                @PathVariable("date")
                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        service.deleteException(dentistId, date);
    }

    @GetMapping("/{dentistId}/time-off")
    public List<TimeOffView> listTimeOff(@PathVariable("dentistId") Long dentistId) {
        return service.listTimeOff(dentistId);
    }

    @PostMapping("/{dentistId}/time-off")
    @ResponseStatus(HttpStatus.CREATED)
    public TimeOffView createTimeOff(@PathVariable("dentistId") Long dentistId,
                                     @Valid @RequestBody TimeOffRequest request) {
        return service.createTimeOff(dentistId, request);
    }

    @PutMapping("/{dentistId}/time-off/{timeOffId}")
    public TimeOffView updateTimeOff(@PathVariable("dentistId") Long dentistId,
                                     @PathVariable("timeOffId") Long timeOffId,
                                     @Valid @RequestBody TimeOffRequest request) {
        return service.updateTimeOff(dentistId, timeOffId, request);
    }

    @DeleteMapping("/{dentistId}/time-off/{timeOffId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTimeOff(@PathVariable("dentistId") Long dentistId,
                              @PathVariable("timeOffId") Long timeOffId) {
        service.deleteTimeOff(dentistId, timeOffId);
    }
}
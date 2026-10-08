package com.dentalclinic.scheduling.api;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dentalclinic.scheduling.api.dto.ClinicScheduleView;
import com.dentalclinic.scheduling.api.dto.DayRequest;
import com.dentalclinic.scheduling.api.dto.ScheduleExceptionRequest;
import com.dentalclinic.scheduling.api.dto.ScheduleExceptionView;
import com.dentalclinic.scheduling.api.dto.WeekRequest;
import com.dentalclinic.scheduling.application.ClinicScheduleService;

import jakarta.validation.Valid;

/**
 * Horario de la clínica (RF-SC-01/02/05). Matriz §7.4: solo OWNER escribe; el resto de roles
 * internos lee (lo aplica {@code EndpointAccessFilter}).
 */
@RestController
@RequestMapping("/api/schedules/clinic")
public class ClinicScheduleController {

    private final ClinicScheduleService service;

    public ClinicScheduleController(ClinicScheduleService service) {
        this.service = service;
    }

    @GetMapping
    public ClinicScheduleView get() {
        return service.get();
    }

    @PutMapping
    public ClinicScheduleView replaceWeek(@Valid @RequestBody WeekRequest request) {
        return service.replaceWeek(request);
    }

    @PutMapping("/{day:[1-7]}")
    public ClinicScheduleView replaceDay(@PathVariable("day") int day,
                                         @Valid @RequestBody DayRequest request) {
        return service.replaceDay(day, request);
    }

    @GetMapping("/exceptions")
    public List<ScheduleExceptionView> listExceptions() {
        return service.listExceptions();
    }

    @PutMapping("/exceptions/{date}")
    public ScheduleExceptionView putException(
            @PathVariable("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody ScheduleExceptionRequest request) {
        return service.putException(date, request);
    }

    @DeleteMapping("/exceptions/{date}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteException(
            @PathVariable("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        service.deleteException(date);
    }
}
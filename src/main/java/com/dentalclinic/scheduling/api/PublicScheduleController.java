package com.dentalclinic.scheduling.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dentalclinic.scheduling.api.dto.PublicScheduleView;
import com.dentalclinic.scheduling.application.PublicScheduleService;

/** Horario público de la clínica y sus dentistas (RF-SC-08). Sin autenticación. */
@RestController
public class PublicScheduleController {

    private final PublicScheduleService service;

    public PublicScheduleController(PublicScheduleService service) {
        this.service = service;
    }

    @GetMapping("/api/public/schedule")
    public PublicScheduleView get() {
        return service.get();
    }
}
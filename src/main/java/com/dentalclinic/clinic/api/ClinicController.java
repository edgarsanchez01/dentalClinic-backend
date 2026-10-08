package com.dentalclinic.clinic.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dentalclinic.clinic.api.dto.ClinicResponse;
import com.dentalclinic.clinic.api.dto.UpdateClinicRequest;
import com.dentalclinic.clinic.application.ClinicService;
import com.dentalclinic.clinic.application.ClinicService.ClinicData;
import com.dentalclinic.clinic.domain.Clinic;

import jakarta.validation.Valid;

/**
 * Configuración de la clínica (RF-CL-03, matriz §7.4 fila "Clinic": solo OWNER).
 *
 * <p>La ruta {@code /api/clinic} la gobierna {@code EndpointGroup.CLINIC}: OWNER tiene
 * {@code READ_WRITE} y los demás roles quedan denegados por el filtro de la matriz con 403.
 */
@RestController
@RequestMapping("/api/clinic")
public class ClinicController {

    private final ClinicService clinicService;

    public ClinicController(ClinicService clinicService) {
        this.clinicService = clinicService;
    }

    @GetMapping
    public ClinicResponse current() {
        return ClinicResponse.from(clinicService.get());
    }

    @PutMapping
    public ClinicResponse update(@Valid @RequestBody UpdateClinicRequest request) {
        Clinic updated = clinicService.update(new ClinicData(
                request.name(), request.timezone(), request.description(), request.logoUrl(),
                request.address(), request.phone(), request.email(), request.publicInfo(),
                request.brandColor(), request.socialNetworks()));
        return ClinicResponse.from(updated);
    }
}
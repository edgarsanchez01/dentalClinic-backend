package com.dentalclinic.patient.api;

import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.identity.infrastructure.security.CurrentUser;
import com.dentalclinic.patient.api.dto.CreatePatientRequest;
import com.dentalclinic.patient.api.dto.PageResponse;
import com.dentalclinic.patient.api.dto.PatientResponse;
import com.dentalclinic.patient.api.dto.UpdatePatientRequest;
import com.dentalclinic.patient.application.PatientService;
import com.dentalclinic.patient.application.PatientService.PatientData;
import com.dentalclinic.patient.domain.Patient;

import jakarta.validation.Valid;

/**
 * Gestion administrativa de pacientes (matriz §7.4).
 *
 * <p>Los permisos por rol los aplica {@code EndpointAccessFilter} a partir de {@code EndpointGroup}:
 * OWNER y RECEPTIONIST gestionan, DENTIST solo lee y el PATIENT solo se lee a si mismo. La
 * propiedad del paciente (que un PATIENT solo alcance el suyo) es una regla de fila que el filtro
 * no puede expresar, asi que se resuelve aqui y se traduce a 404 (nunca 403): un recurso ajeno es
 * indistinguible de uno inexistente (§8.2).
 */
@RestController
@RequestMapping("/api/patients")
public class PatientController {

    private static final int MAX_PAGE_SIZE = 100;

    private final PatientService patientService;

    public PatientController(PatientService patientService) {
        this.patientService = patientService;
    }

    @GetMapping
    public PageResponse<PatientResponse> list(
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "active", required = false) Boolean active,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {

        AuthenticatedUser principal = CurrentUser.require();
        if (principal.role() == Role.PATIENT) {
            return patientService.findByUserId(principal.userId())
                    .map(patient -> new PageResponse<>(List.of(toDto(patient)), 0, 1, 1, 1))
                    .orElseGet(() -> new PageResponse<>(List.of(), 0, 0, 0, 0));
        }

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Order.asc("lastName"), Sort.Order.asc("firstName")));
        return PageResponse.from(patientService.search(search, active, pageable).map(PatientController::toDto));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PatientResponse create(@Valid @RequestBody CreatePatientRequest request) {
        return toDto(patientService.create(toData(request)));
    }

    @GetMapping("/{id}")
    public PatientResponse get(@PathVariable("id") Long id) {
        Patient patient = patientService.get(id);
        requireSelfWhenPatient(patient);
        return toDto(patient);
    }

    @PatchMapping("/{id}")
    public PatientResponse update(@PathVariable("id") Long id,
                                  @Valid @RequestBody UpdatePatientRequest request) {
        return toDto(patientService.update(id, toData(request), request.active()));
    }

    private void requireSelfWhenPatient(Patient patient) {
        AuthenticatedUser principal = CurrentUser.require();
        if (principal.role() == Role.PATIENT && !Objects.equals(patient.getUserId(), principal.userId())) {
            throw new NotFoundException();
        }
    }

    private static PatientData toData(CreatePatientRequest request) {
        return new PatientData(request.firstName(), request.lastName(), request.email(), request.phone(),
                request.birthDate(), request.administrativeNotes());
    }

    private static PatientData toData(UpdatePatientRequest request) {
        return new PatientData(request.firstName(), request.lastName(), request.email(), request.phone(),
                request.birthDate(), request.administrativeNotes());
    }

    private static PatientResponse toDto(Patient patient) {
        return new PatientResponse(patient.getId(), patient.getUserId(), patient.getFirstName(),
                patient.getLastName(), patient.getEmail(), patient.getPhone(), patient.getBirthDate(),
                patient.getAdministrativeNotes(), patient.isActive(), patient.getCreatedAt(),
                patient.getUpdatedAt());
    }
}
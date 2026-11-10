package com.dentalclinic.clinical.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dentalclinic.clinical.api.dto.CreateTreatmentRequest;
import com.dentalclinic.clinical.api.dto.TreatmentResponse;
import com.dentalclinic.clinical.api.dto.UpdateTreatmentRequest;
import com.dentalclinic.clinical.application.TreatmentService;
import com.dentalclinic.clinical.application.TreatmentService.TreatmentData;
import com.dentalclinic.clinical.domain.Treatment;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.identity.infrastructure.security.CurrentUser;
import com.dentalclinic.odontogram.application.TreatmentToothLinkService;

import jakarta.validation.Valid;

/**
 * Registro clinico de tratamientos de un paciente (RF-TR-04/05/06).
 *
 * <p>Permisos por la matriz 7.4 ({@code /api/patients/{id}/clinical/**}): OWNER lee; DENTIST lee y
 * escribe; RECEPTIONIST y PATIENT no tienen acceso. Los aplica {@code EndpointAccessFilter}. La
 * referencia a la cita se fija en la creacion y no se modifica despues.
 */
@RestController
@RequestMapping("/api/patients/{id}/clinical/treatments")
public class TreatmentController {

    private final TreatmentService treatmentService;
    private final TreatmentToothLinkService toothLinkService;

    public TreatmentController(TreatmentService treatmentService,
                               TreatmentToothLinkService toothLinkService) {
        this.treatmentService = treatmentService;
        this.toothLinkService = toothLinkService;
    }

    @GetMapping
    public List<TreatmentResponse> list(@PathVariable("id") Long patientId) {
        List<Treatment> treatments = treatmentService.list(patientId);
        Map<Long, List<Integer>> toothFdis = toothLinkService.toothFdisByTreatmentIds(patientId,
                treatments.stream().map(Treatment::getId).toList());
        return treatments.stream()
                .map(treatment -> TreatmentResponse.from(treatment,
                        toothFdis.getOrDefault(treatment.getId(), List.of())))
                .toList();
    }

    @GetMapping("/{treatmentId}")
    public TreatmentResponse get(@PathVariable("id") Long patientId,
                                 @PathVariable("treatmentId") Long treatmentId) {
        return toResponse(patientId, treatmentService.get(patientId, treatmentId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TreatmentResponse create(@PathVariable("id") Long patientId,
                                    @Valid @RequestBody CreateTreatmentRequest request) {
        AuthenticatedUser principal = CurrentUser.require();
        Treatment created = treatmentService.create(patientId, data(request), principal);
        return toResponse(patientId, created);
    }

    @PutMapping("/{treatmentId}")
    public TreatmentResponse update(@PathVariable("id") Long patientId,
                                    @PathVariable("treatmentId") Long treatmentId,
                                    @Valid @RequestBody UpdateTreatmentRequest request) {
        TreatmentData data = new TreatmentData(request.description(), request.diagnosis(),
                request.status(), null, request.notes(), request.price());
        return toResponse(patientId, treatmentService.update(patientId, treatmentId, data));
    }

    /** Compone la respuesta con las piezas vinculadas al tratamiento (RF-OD-05). */
    private TreatmentResponse toResponse(Long patientId, Treatment treatment) {
        List<Integer> toothFdis = toothLinkService
                .toothFdisByTreatmentIds(patientId, List.of(treatment.getId()))
                .getOrDefault(treatment.getId(), List.of());
        return TreatmentResponse.from(treatment, toothFdis);
    }

    private static TreatmentData data(CreateTreatmentRequest request) {
        return new TreatmentData(request.description(), request.diagnosis(), request.status(),
                request.appointmentId(), request.notes(), request.price());
    }
}
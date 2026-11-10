package com.dentalclinic.clinical.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import com.dentalclinic.clinical.api.dto.ClinicalAppointmentContextResponse;

import com.dentalclinic.clinical.api.dto.CreateTreatmentEvolutionCorrectionRequest;
import com.dentalclinic.clinical.api.dto.CreateTreatmentEvolutionRequest;
import com.dentalclinic.clinical.api.dto.TreatmentEvolutionResponse;
import com.dentalclinic.clinical.application.TreatmentEvolutionService;
import com.dentalclinic.clinical.application.TreatmentEvolutionService.TreatmentEvolutionView;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.identity.infrastructure.security.CurrentUser;

import jakarta.validation.Valid;

/**
 * Evolucion clinica de un tratamiento (RF-TR-07, EVO-01, AD-26).
 *
 * <p>Permisos por la matriz 7.4 ({@code /api/patients/{id}/clinical/**}): OWNER y DENTIST leen y
 * escriben (el OWNER siempre tiene perfil de dentista, AD-11); RECEPTIONIST y PATIENT no tienen
 * acceso. Los aplica {@code EndpointAccessFilter}. Las entradas son <strong>append-only</strong>:
 * no hay {@code PUT}/{@code PATCH}/{@code DELETE}.
 */
@RestController
@RequestMapping("/api/patients/{id}/clinical/treatments/{treatmentId}/evolution")
public class TreatmentEvolutionController {

    private final TreatmentEvolutionService evolutionService;

    public TreatmentEvolutionController(TreatmentEvolutionService evolutionService) {
        this.evolutionService = evolutionService;
    }

    @GetMapping
    public List<TreatmentEvolutionResponse> list(@PathVariable("id") Long patientId,
                                                  @PathVariable("treatmentId") Long treatmentId,
                                                  @RequestParam(defaultValue = "false") boolean generalOnly) {
        return evolutionService.list(patientId, treatmentId, generalOnly).stream()
                .map(TreatmentEvolutionController::toDto).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TreatmentEvolutionResponse append(@PathVariable("id") Long patientId,
                                             @PathVariable("treatmentId") Long treatmentId,
                                             @Valid @RequestBody CreateTreatmentEvolutionRequest request) {
        AuthenticatedUser principal = CurrentUser.require();
        return toDto(evolutionService.append(patientId, treatmentId, principal,
                request.content(), request.appointmentContext() == null ? null : request.appointmentContext().toSelection()));
    }

    /**
     * Corrige una entrada de evolucion conservando la original (append-only). Cualquier OWNER/DENTIST
     * del grupo clinico puede corregir; no se exige ser el autor ni se amplia la matriz de permisos.
     */
    @PostMapping("/{evolutionId}/corrections")
    @ResponseStatus(HttpStatus.CREATED)
    public TreatmentEvolutionResponse correctEvolution(@PathVariable("id") Long patientId,
                                                       @PathVariable("treatmentId") Long treatmentId,
                                                       @PathVariable Long evolutionId,
                                                       @Valid @RequestBody CreateTreatmentEvolutionCorrectionRequest request) {
        AuthenticatedUser principal = CurrentUser.require();
        return toDto(evolutionService.correct(patientId, treatmentId, evolutionId, principal,
                request.content(), request.correctionReason()));
    }

    public static TreatmentEvolutionResponse toDto(TreatmentEvolutionView view) {
        return new TreatmentEvolutionResponse(view.id(), view.content(), view.authorDentistId(),
                view.authorName(), view.createdAt(), view.treatmentId(),
                ClinicalAppointmentContextResponse.from(view.appointmentContext()),
                view.correctsRecordId(), view.correctionReason());
    }
}

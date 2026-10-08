package com.dentalclinic.clinical.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dentalclinic.clinical.api.dto.ClinicalNoteResponse;
import com.dentalclinic.clinical.api.dto.ClinicalRecordResponse;
import com.dentalclinic.clinical.api.dto.CreateClinicalNoteRequest;
import com.dentalclinic.clinical.api.dto.SaveClinicalRecordRequest;
import com.dentalclinic.clinical.application.ClinicalService;
import com.dentalclinic.clinical.application.ClinicalService.ClinicalNoteView;
import com.dentalclinic.clinical.application.ClinicalService.ClinicalRecordView;
import com.dentalclinic.clinical.application.ClinicalService.RecordData;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.identity.infrastructure.security.CurrentUser;

import jakarta.validation.Valid;

/**
 * Expediente clinico de un paciente (matriz §7.4, grupo {@code /api/patients/{id}/clinical}).
 *
 * <p>OWNER lee; DENTIST lee y escribe; RECEPTIONIST y PATIENT no tienen acceso (RF-CX-06/07). Los
 * permisos los aplica {@code EndpointAccessFilter}. Las notas son inmutables (RF-CX-04): no hay
 * {@code PUT}/{@code PATCH}/{@code DELETE} sobre ellas.
 */
@RestController
@RequestMapping("/api/patients/{id}/clinical")
public class ClinicalController {

    private final ClinicalService clinicalService;

    public ClinicalController(ClinicalService clinicalService) {
        this.clinicalService = clinicalService;
    }

    @GetMapping
    public ClinicalRecordResponse getRecord(@PathVariable("id") Long patientId) {
        return toDto(clinicalService.getRecord(patientId));
    }

    @PutMapping
    public ClinicalRecordResponse saveRecord(@PathVariable("id") Long patientId,
                                             @Valid @RequestBody SaveClinicalRecordRequest request) {
        return toDto(clinicalService.saveRecord(patientId, new RecordData(
                request.medicalBackground(), request.allergies(), request.clinicalHistory(),
                request.generalObservations())));
    }

    @GetMapping("/notes")
    public List<ClinicalNoteResponse> listNotes(@PathVariable("id") Long patientId) {
        return clinicalService.listNotes(patientId).stream().map(ClinicalController::toDto).toList();
    }

    @PostMapping("/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public ClinicalNoteResponse addNote(@PathVariable("id") Long patientId,
                                        @Valid @RequestBody CreateClinicalNoteRequest request) {
        AuthenticatedUser principal = CurrentUser.require();
        return toDto(clinicalService.addNote(patientId, principal.userId(), request.content()));
    }

    private static ClinicalRecordResponse toDto(ClinicalRecordView view) {
        return new ClinicalRecordResponse(view.patientId(), view.medicalBackground(), view.allergies(),
                view.clinicalHistory(), view.generalObservations(), view.updatedAt());
    }

    private static ClinicalNoteResponse toDto(ClinicalNoteView view) {
        return new ClinicalNoteResponse(view.id(), view.content(), view.authorDentistId(),
                view.authorName(), view.createdAt());
    }
}
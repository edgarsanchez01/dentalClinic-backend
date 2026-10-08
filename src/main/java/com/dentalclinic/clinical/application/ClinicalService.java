package com.dentalclinic.clinical.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.clinic.application.DentistService;
import com.dentalclinic.clinical.domain.ClinicalNote;
import com.dentalclinic.clinical.domain.ClinicalRecord;
import com.dentalclinic.clinical.infrastructure.repository.ClinicalNoteRepository;
import com.dentalclinic.clinical.infrastructure.repository.ClinicalRecordRepository;
import com.dentalclinic.patient.application.PatientService;

/**
 * Expediente clinico (RF-CX-01/02/04/06/07/08, Fase 4).
 *
 * <p>La autorizacion de rol la aplica la cadena de seguridad (matriz §7.4): este servicio asume
 * que quien escribe es DENTIST. Aqui solo se resuelve la existencia del paciente y del expediente,
 * la creacion perezosa del expediente y la inmutabilidad de las notas.
 *
 * <p>Las notas son <strong>append-only</strong> (RF-CX-04): no hay operacion de actualizacion ni de
 * borrado, ni aqui ni en el repositorio.
 */
@Service
public class ClinicalService {

    private final PatientService patientService;
    private final DentistService dentistService;
    private final ClinicalRecordRepository clinicalRecordRepository;
    private final ClinicalNoteRepository clinicalNoteRepository;
    private final Clock clock;

    public ClinicalService(PatientService patientService,
                           DentistService dentistService,
                           ClinicalRecordRepository clinicalRecordRepository,
                           ClinicalNoteRepository clinicalNoteRepository,
                           Clock clock) {
        this.patientService = patientService;
        this.dentistService = dentistService;
        this.clinicalRecordRepository = clinicalRecordRepository;
        this.clinicalNoteRepository = clinicalNoteRepository;
        this.clock = clock;
    }

    /**
     * Expediente del paciente. Si aun no existe, devuelve uno vacio (no es un error: el expediente
     * se crea la primera vez que un DENTIST lo guarda).
     *
     * @throws com.dentalclinic.identity.domain.exception.NotFoundException si el paciente no existe
     */
    @Transactional(readOnly = true)
    public ClinicalRecordView getRecord(Long patientId) {
        patientService.get(patientId);
        return clinicalRecordRepository.findByPatientId(patientId)
                .map(ClinicalService::toView)
                .orElseGet(() -> new ClinicalRecordView(patientId, null, null, null, null, null));
    }

    /** Crea o reemplaza el expediente del paciente. */
    @Transactional
    public ClinicalRecordView saveRecord(Long patientId, RecordData data) {
        patientService.get(patientId);
        ClinicalRecord record = clinicalRecordRepository.findByPatientId(patientId)
                .orElseGet(() -> ClinicalRecord.createFor(patientId));
        record.update(optional(data.medicalBackground()), optional(data.allergies()),
                optional(data.clinicalHistory()), optional(data.generalObservations()), clock.instant());
        return toView(clinicalRecordRepository.save(record));
    }

    /** Notas del paciente en orden cronologico. Vacio si el paciente aun no tiene expediente. */
    @Transactional(readOnly = true)
    public List<ClinicalNoteView> listNotes(Long patientId) {
        patientService.get(patientId);
        return clinicalRecordRepository.findByPatientId(patientId)
                .map(record -> clinicalNoteRepository
                        .findByClinicalRecordIdOrderByCreatedAtAscIdAsc(record.getId())
                        .stream().map(this::toView).toList())
                .orElseGet(List::of);
    }

    /**
     * Agrega una nota inmutable escrita por el usuario DENTIST autenticado. Si el paciente no tiene
     * expediente todavia, se crea uno vacio para poder colgar la nota.
     */
    @Transactional
    public ClinicalNoteView addNote(Long patientId, Long authorUserId, String content) {
        patientService.get(patientId);
        ClinicalRecord record = clinicalRecordRepository.findByPatientId(patientId)
                .orElseGet(() -> clinicalRecordRepository.save(ClinicalRecord.createFor(patientId)));
        Long authorDentistId = dentistService.getByUserId(authorUserId).getId();
        ClinicalNote note = ClinicalNote.write(record.getId(), authorDentistId,
                required(content), clock.instant());
        return toView(clinicalNoteRepository.save(note));
    }

    private ClinicalNoteView toView(ClinicalNote note) {
        var author = dentistService.get(note.getAuthorDentistId());
        String authorName = author.getFirstName() + " " + author.getLastName();
        return new ClinicalNoteView(note.getId(), note.getContent(), note.getAuthorDentistId(),
                authorName, note.getCreatedAt());
    }

    private static ClinicalRecordView toView(ClinicalRecord record) {
        return new ClinicalRecordView(record.getPatientId(), record.getMedicalBackground(),
                record.getAllergies(), record.getClinicalHistory(), record.getGeneralObservations(),
                record.getUpdatedAt());
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("content es obligatorio");
        }
        return value.trim();
    }

    private static String optional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Contenido editable del expediente. */
    public record RecordData(String medicalBackground, String allergies, String clinicalHistory,
                             String generalObservations) {
    }

    /** Vista del expediente. */
    public record ClinicalRecordView(Long patientId, String medicalBackground, String allergies,
                                     String clinicalHistory, String generalObservations,
                                     Instant updatedAt) {
    }

    /** Vista de una nota, con el nombre del dentista autor resuelto. */
    public record ClinicalNoteView(Long id, String content, Long authorDentistId, String authorName,
                                   Instant createdAt) {
    }
}
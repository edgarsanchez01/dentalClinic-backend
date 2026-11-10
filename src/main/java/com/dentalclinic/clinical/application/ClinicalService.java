package com.dentalclinic.clinical.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.clinic.application.DentistService;
import com.dentalclinic.clinic.domain.Dentist;
import com.dentalclinic.clinical.domain.ClinicalNote;
import com.dentalclinic.clinical.domain.ClinicalRecord;
import com.dentalclinic.clinical.infrastructure.repository.ClinicalNoteRepository;
import com.dentalclinic.clinical.infrastructure.repository.ClinicalRecordRepository;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.patient.application.PatientService;
import com.dentalclinic.appointment.application.AppointmentClinicalContextService;
import com.dentalclinic.appointment.application.AppointmentClinicalContextService.Selection;
import com.dentalclinic.appointment.domain.RecordedAppointmentContext;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/**
 * Expediente clinico (RF-CX-01/02/04/06/07/08, Fase 4).
 *
 * <p>La autorizacion de rol la aplica la cadena de seguridad (matriz §7.4): este servicio asume
 * que quien escribe es personal clinico (OWNER o DENTIST; el OWNER siempre tiene perfil de
 * dentista, AD-11). Aqui solo se resuelve la existencia del paciente y del expediente,
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
    private final ClinicalAuthorService authors;
    private final AppointmentClinicalContextService contexts;

    public ClinicalService(PatientService patientService,
                           DentistService dentistService,
                           ClinicalRecordRepository clinicalRecordRepository,
                           ClinicalNoteRepository clinicalNoteRepository,
                            Clock clock, ClinicalAuthorService authors, AppointmentClinicalContextService contexts) {
        this.patientService = patientService;
        this.dentistService = dentistService;
        this.clinicalRecordRepository = clinicalRecordRepository;
        this.clinicalNoteRepository = clinicalNoteRepository;
        this.clock = clock;
        this.authors = authors;
        this.contexts = contexts;
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

    /** Intake declarations share the unique record; never replace professional text columns. */
    @Transactional
    public Long ensureRecordForIntake(Long patientId) {
        patientService.get(patientId);
        clinicalRecordRepository.ensureEmptyRecord(patientId);
        return clinicalRecordRepository.findByPatientId(patientId).orElseThrow().getId();
    }

    /** Crea o reemplaza el expediente del paciente. */
    @Transactional
    public ClinicalRecordView saveRecord(Long patientId, RecordData data) {
        patientService.get(patientId);
        clinicalRecordRepository.ensureEmptyRecord(patientId);
        ClinicalRecord record = clinicalRecordRepository.findByPatientId(patientId).orElseThrow();
        record.update(optional(data.medicalBackground()), optional(data.allergies()),
                optional(data.clinicalHistory()), optional(data.generalObservations()), clock.instant());
        return toView(clinicalRecordRepository.save(record));
    }

    /** Notas del paciente en orden cronologico. Vacio si el paciente aun no tiene expediente. */
    @Transactional(readOnly = true)
    public List<ClinicalNoteView> listNotes(Long patientId) {
        return listNotes(patientId, false);
    }

    @Transactional(readOnly = true)
    public List<ClinicalNoteView> listNotes(Long patientId, boolean generalOnly) {
        patientService.get(patientId);
        return clinicalRecordRepository.findByPatientId(patientId)
                .map(record -> (generalOnly ? clinicalNoteRepository.findByClinicalRecordIdAndAppointmentContextIsNullOrderByCreatedAtAscIdAsc(record.getId())
                        : clinicalNoteRepository.findByClinicalRecordIdOrderByCreatedAtAscIdAsc(record.getId())))
                .map(this::toViews)
                .orElseGet(List::of);
    }

    /**
     * Agrega una nota inmutable escrita por el usuario clínico autenticado. Si el paciente no tiene
     * expediente todavia, se crea uno vacio para poder colgar la nota.
     */
    @Transactional
    public ClinicalNoteView addNote(Long patientId, AuthenticatedUser actor, String content, Selection selection) {
        Long authorDentistId = authors.require(actor.userId()).getId();
        RecordedAppointmentContext context = contexts.capture(patientId, selection, actor);
        patientService.get(patientId);
        clinicalRecordRepository.ensureEmptyRecord(patientId);
        ClinicalRecord record = clinicalRecordRepository.findByPatientId(patientId).orElseThrow();
        ClinicalNote note = ClinicalNote.write(record.getId(), authorDentistId,
                required(content), clock.instant(), context);
        return toView(clinicalNoteRepository.save(note));
    }

    /**
     * Registra una correccion de una nota (Fase 1B.2). La correccion es una fila nueva que apunta a
     * la RAIZ de la cadena: si {@code noteId} es a su vez una correccion, se corrige su raiz. Conserva
     * el expediente y el contexto historico del original (sin re-capturar snapshot) y sustituye el
     * contenido en su totalidad. La nota original no se modifica ni se elimina.
     *
     * <p>Autorizacion: cualquier OWNER/DENTIST del grupo clinico puede corregir; no se exige ser el
     * autor original y no se amplia la matriz de permisos.
     */
    @Transactional
    public ClinicalNoteView correct(Long patientId, Long noteId, AuthenticatedUser actor,
                                    String content, String reason) {
        Long authorDentistId = authors.require(actor.userId()).getId();
        patientService.get(patientId);
        ClinicalRecord record = clinicalRecordRepository.findByPatientId(patientId)
                .orElseThrow(NotFoundException::new);
        ClinicalNote target = clinicalNoteRepository.findByIdAndClinicalRecordId(noteId, record.getId())
                .orElseThrow(NotFoundException::new);
        ClinicalNote root = target.isCorrection()
                ? clinicalNoteRepository.findById(target.getCorrectsNoteId()).orElseThrow(NotFoundException::new)
                : target;
        ClinicalNote correction = ClinicalNote.correct(root, authorDentistId, required(content),
                reason, clock.instant());
        return toView(clinicalNoteRepository.save(correction));
    }

    public ClinicalNoteView toView(ClinicalNote note) {
        return toView(note, authorName(note.getAuthorDentistId()));
    }

    public ClinicalNoteView toView(ClinicalNote note, String authorName) {
        return new ClinicalNoteView(note.getId(), note.getContent(), note.getAuthorDentistId(),
                authorName, note.getCreatedAt(), note.getAppointmentContext(),
                note.getCorrectsNoteId(), note.getCorrectionReason());
    }

    /** Resuelve nombres de autor en un solo lote para evitar N+1 al ensamblar cadenas. */
    public Map<Long, String> authorNames(Collection<Long> dentistIds) {
        return dentistService.findByIds(dentistIds).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> fullName(e.getValue())));
    }

    public List<ClinicalNoteView> toViews(List<ClinicalNote> notes) {
        Map<Long, String> names = authorNames(notes.stream().map(ClinicalNote::getAuthorDentistId).toList());
        return notes.stream().map(note -> toView(note, resolveName(names, note.getAuthorDentistId()))).toList();
    }

    private String resolveName(Map<Long, String> names, Long dentistId) {
        return names.getOrDefault(dentistId, "Autor desconocido");
    }

    private String authorName(Long dentistId) {
        return fullName(dentistService.get(dentistId));
    }

    private static String fullName(Dentist dentist) {
        return dentist.getFirstName() + " " + dentist.getLastName();
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
                                    Instant createdAt, RecordedAppointmentContext appointmentContext,
                                    Long correctsRecordId, String correctionReason) {
    }
}

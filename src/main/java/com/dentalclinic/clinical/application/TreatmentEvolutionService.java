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
import com.dentalclinic.clinical.domain.TreatmentEvolution;
import com.dentalclinic.clinical.infrastructure.repository.TreatmentEvolutionRepository;
import com.dentalclinic.clinical.infrastructure.repository.TreatmentRepository;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.patient.application.PatientService;
import com.dentalclinic.appointment.application.AppointmentClinicalContextService;
import com.dentalclinic.appointment.application.AppointmentClinicalContextService.Selection;
import com.dentalclinic.appointment.domain.RecordedAppointmentContext;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/**
 * Evolucion clinica de un tratamiento (RF-TR-07, EVO-01, AD-26).
 *
 * <p>Entradas <strong>append-only</strong>: se agregan y se listan; no hay edicion ni borrado. Cada
 * entrada registra al usuario autenticado como autor, resuelto por su perfil {@code Dentist} (el
 * {@code OWNER} siempre lo tiene, AD-11), y una marca de tiempo generada en el servidor.
 *
 * <p>La autorizacion de rol la aplica la cadena de seguridad (matriz 7.4): el grupo
 * {@code /api/patients/*&#47;clinical/**} da lectura y escritura a OWNER y DENTIST; RECEPTIONIST y
 * PATIENT no tienen acceso. Aqui solo se resuelve la existencia del paciente, la pertenencia del
 * tratamiento al paciente y la coherencia del contenido.
 *
 * <p>No implementa captura automatica de cambios de estado, historial retroactivo, vínculos con
 * citas/sesiones/piezas ni multisesion.
 */
@Service
public class TreatmentEvolutionService {

    private final PatientService patientService;
    private final TreatmentRepository treatmentRepository;
    private final TreatmentEvolutionRepository evolutionRepository;
    private final DentistService dentistService;
    private final Clock clock;
    private final ClinicalAuthorService authors;
    private final AppointmentClinicalContextService contexts;

    public TreatmentEvolutionService(PatientService patientService,
                                     TreatmentRepository treatmentRepository,
                                     TreatmentEvolutionRepository evolutionRepository,
                                     DentistService dentistService,
                                      Clock clock, ClinicalAuthorService authors, AppointmentClinicalContextService contexts) {
        this.patientService = patientService;
        this.treatmentRepository = treatmentRepository;
        this.evolutionRepository = evolutionRepository;
        this.dentistService = dentistService;
        this.clock = clock;
        this.authors = authors;
        this.contexts = contexts;
    }

    /** Entradas de evolucion del tratamiento, en orden cronologico estable por {@code createdAt}/{@code id}. */
    @Transactional(readOnly = true)
    public List<TreatmentEvolutionView> list(Long patientId, Long treatmentId) {
        return list(patientId, treatmentId, false);
    }

    @Transactional(readOnly = true)
    public List<TreatmentEvolutionView> list(Long patientId, Long treatmentId, boolean generalOnly) {
        requireTreatment(patientId, treatmentId);
        return toViews((generalOnly ? evolutionRepository.findByTreatmentIdAndAppointmentContextIsNullOrderByCreatedAtAscIdAsc(treatmentId)
                : evolutionRepository.findByTreatmentIdOrderByCreatedAtAscIdAsc(treatmentId)));
    }

    /** Agrega una entrada inmutable escrita por el usuario autenticado. */
    @Transactional
    public TreatmentEvolutionView append(Long patientId, Long treatmentId, AuthenticatedUser actor,
                                         String content, Selection selection) {
        Long authorDentistId = authors.require(actor.userId()).getId();
        requireTreatment(patientId, treatmentId);
        RecordedAppointmentContext context = contexts.capture(patientId, selection, actor);
        TreatmentEvolution entry = TreatmentEvolution.write(treatmentId, authorDentistId, content,
                clock.instant(), context);
        return toView(evolutionRepository.save(entry));
    }

    /**
     * Registra una correccion de una entrada de evolucion (Fase 1B.2). La correccion es una fila
     * nueva que apunta a la RAIZ de la cadena: si {@code evolutionId} es a su vez una correccion, se
     * corrige su raiz. Conserva el tratamiento y el contexto historico del original (sin re-capturar
     * snapshot) y sustituye el contenido en su totalidad. La entrada original no se modifica ni se
     * elimina.
     *
     * <p>Autorizacion: cualquier OWNER/DENTIST del grupo clinico puede corregir; no se exige ser el
     * autor original y no se amplia la matriz de permisos.
     */
    @Transactional
    public TreatmentEvolutionView correct(Long patientId, Long treatmentId, Long evolutionId,
                                          AuthenticatedUser actor, String content, String reason) {
        Long authorDentistId = authors.require(actor.userId()).getId();
        requireTreatment(patientId, treatmentId);
        TreatmentEvolution target = evolutionRepository.findByIdAndTreatmentId(evolutionId, treatmentId)
                .orElseThrow(NotFoundException::new);
        TreatmentEvolution root = target.isCorrection()
                ? evolutionRepository.findById(target.getCorrectsEvolutionId()).orElseThrow(NotFoundException::new)
                : target;
        TreatmentEvolution correction = TreatmentEvolution.correct(root, authorDentistId, content,
                reason, clock.instant());
        return toView(evolutionRepository.save(correction));
    }

    /**
     * Verifica que el paciente exista y que el tratamiento le pertenezca. Un tratamiento de otro
     * paciente se trata como inexistente (404), sin revelar que existe (Architecture.md §8.2).
     */
    private void requireTreatment(Long patientId, Long treatmentId) {
        patientService.get(patientId);
        treatmentRepository.findByIdAndPatientId(treatmentId, patientId)
                .orElseThrow(NotFoundException::new);
    }

    public TreatmentEvolutionView toView(TreatmentEvolution entry) {
        return toView(entry, authorName(entry.getAuthorDentistId()));
    }

    public TreatmentEvolutionView toView(TreatmentEvolution entry, String authorName) {
        return new TreatmentEvolutionView(entry.getId(), entry.getContent(),
                entry.getAuthorDentistId(), authorName, entry.getCreatedAt(), entry.getTreatmentId(),
                entry.getAppointmentContext(), entry.getCorrectsEvolutionId(), entry.getCorrectionReason());
    }

    /** Resuelve nombres de autor en un solo lote para evitar N+1 al ensamblar cadenas. */
    public Map<Long, String> authorNames(Collection<Long> dentistIds) {
        return dentistService.findByIds(dentistIds).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> fullName(e.getValue())));
    }

    public List<TreatmentEvolutionView> toViews(List<TreatmentEvolution> entries) {
        Map<Long, String> names = authorNames(entries.stream().map(TreatmentEvolution::getAuthorDentistId).toList());
        return entries.stream()
                .map(entry -> toView(entry, names.getOrDefault(entry.getAuthorDentistId(), "Autor desconocido")))
                .toList();
    }

    private String authorName(Long dentistId) {
        return fullName(dentistService.get(dentistId));
    }

    private static String fullName(Dentist dentist) {
        return dentist.getFirstName() + " " + dentist.getLastName();
    }

    /** Vista de una entrada de evolucion, con el nombre del dentista autor resuelto. */
    public record TreatmentEvolutionView(Long id, String content, Long authorDentistId,
                                          String authorName, Instant createdAt, Long treatmentId,
                                          RecordedAppointmentContext appointmentContext,
                                          Long correctsRecordId, String correctionReason) {
    }
}

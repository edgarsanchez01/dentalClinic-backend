package com.dentalclinic.odontogram.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.time.temporal.ChronoUnit;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import com.dentalclinic.appointment.application.AppointmentClinicalContextService;
import com.dentalclinic.appointment.application.AppointmentClinicalContextService.Selection;
import com.dentalclinic.appointment.domain.RecordedAppointmentContext;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.odontogram.domain.ToothNotesHistory;
import com.dentalclinic.odontogram.domain.ToothSurfaceNotesHistory;
import com.dentalclinic.odontogram.domain.ToothTreatmentHistory;
import com.dentalclinic.odontogram.domain.TreatmentLinkChange;
import com.dentalclinic.odontogram.infrastructure.repository.ToothNotesHistoryRepository;
import com.dentalclinic.odontogram.infrastructure.repository.ToothSurfaceNotesHistoryRepository;
import com.dentalclinic.odontogram.infrastructure.repository.ToothTreatmentHistoryRepository;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.clinic.application.DentistService;
import com.dentalclinic.clinical.application.TreatmentService;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.odontogram.domain.ToothCondition;
import com.dentalclinic.odontogram.domain.ToothHistory;
import com.dentalclinic.odontogram.domain.ToothRecord;
import com.dentalclinic.odontogram.domain.ToothRecordAlreadyExistsException;
import com.dentalclinic.odontogram.domain.ToothSurface;
import com.dentalclinic.odontogram.domain.ToothSurfaceAlreadyExistsException;
import com.dentalclinic.odontogram.domain.ToothSurfaceHistory;
import com.dentalclinic.odontogram.domain.ToothSurfaceRecord;
import com.dentalclinic.odontogram.domain.TreatmentTooth;
import com.dentalclinic.odontogram.infrastructure.repository.ToothHistoryRepository;
import com.dentalclinic.odontogram.infrastructure.repository.ToothRecordRepository;
import com.dentalclinic.odontogram.infrastructure.repository.ToothSurfaceHistoryRepository;
import com.dentalclinic.odontogram.infrastructure.repository.ToothSurfaceRecordRepository;
import com.dentalclinic.odontogram.infrastructure.repository.TreatmentToothRepository;
import com.dentalclinic.patient.application.PatientService;

/**
 * Odontograma de un paciente (RF-OD-01/02/03/04/05/08, F7.4.3.1 y F7.4.3.2).
 *
 * <p>La autorización de rol la aplica la cadena de seguridad (matriz §7.4): el grupo
 * {@code /api/patients/*&#47;odontogram/**} da escritura a OWNER y DENTIST (el OWNER siempre
 * tiene perfil de dentista, AD-11), y lectura limitada al propio resumen a PATIENT. Aquí se
 * resuelve, además, la <strong>pertenencia
 * del PATIENT</strong> (§7.5/§8.2): un paciente solo alcanza su propio odontograma y un recurso
 * ajeno es indistinguible de uno inexistente (404).
 *
 * <p>El estado vigente de cada pieza vive en {@link ToothRecord} y el de cada superficie en
 * {@link ToothSurfaceRecord} (RF-OD-02). Cada cambio de condición —de pieza o de superficie— se
 * registra en su historial append-only ({@link ToothHistory}, {@link ToothSurfaceHistory}). El
 * vínculo con tratamientos (RF-OD-05) se modela en {@link TreatmentTooth}.
 */
@Service
public class OdontogramService {

    private final PatientService patientService;
    private final TreatmentService treatmentService;
    private final DentistService dentistService;
    private final ToothRecordRepository toothRecordRepository;
    private final ToothHistoryRepository toothHistoryRepository;
    private final ToothSurfaceRecordRepository toothSurfaceRecordRepository;
    private final ToothSurfaceHistoryRepository toothSurfaceHistoryRepository;
    private final TreatmentToothRepository treatmentToothRepository;
    private final Clock clock;
    private final AppointmentClinicalContextService contexts;
    private final ToothNotesHistoryRepository notesHistory;
    private final ToothSurfaceNotesHistoryRepository surfaceNotesHistory;
    private final ToothTreatmentHistoryRepository treatmentHistory;
    private final EntityManager entityManager;

    public OdontogramService(PatientService patientService, TreatmentService treatmentService,
                             DentistService dentistService, ToothRecordRepository toothRecordRepository,
                             ToothHistoryRepository toothHistoryRepository,
                             ToothSurfaceRecordRepository toothSurfaceRecordRepository,
                             ToothSurfaceHistoryRepository toothSurfaceHistoryRepository,
                              TreatmentToothRepository treatmentToothRepository, Clock clock,
                              AppointmentClinicalContextService contexts, ToothNotesHistoryRepository notesHistory,
                              ToothSurfaceNotesHistoryRepository surfaceNotesHistory,
                              ToothTreatmentHistoryRepository treatmentHistory, EntityManager entityManager) {
        this.patientService = patientService;
        this.treatmentService = treatmentService;
        this.dentistService = dentistService;
        this.toothRecordRepository = toothRecordRepository;
        this.toothHistoryRepository = toothHistoryRepository;
        this.toothSurfaceRecordRepository = toothSurfaceRecordRepository;
        this.toothSurfaceHistoryRepository = toothSurfaceHistoryRepository;
        this.treatmentToothRepository = treatmentToothRepository;
        this.clock = clock;
        this.contexts = contexts;
        this.notesHistory = notesHistory;
        this.surfaceNotesHistory = surfaceNotesHistory;
        this.treatmentHistory = treatmentHistory;
        this.entityManager = entityManager;
    }

    /** Piezas registradas del paciente, ordenadas por código FDI ascendente, con sus superficies. */
    @Transactional(readOnly = true)
    public List<ToothView> list(Long patientId, AuthenticatedUser actor) {
        assertReadAccess(patientId, actor);
        List<ToothRecord> records = toothRecordRepository.findByPatientIdOrderByToothFdiAsc(patientId);
        if (records.isEmpty()) {
            return List.of();
        }
        List<Long> recordIds = records.stream().map(ToothRecord::getId).toList();
        Map<Long, List<ToothHistory>> histories = toothHistoryRepository
                .findByToothRecordIdInOrderByChangedAtAscIdAsc(recordIds).stream()
                .collect(Collectors.groupingBy(ToothHistory::getToothRecordId));
        Map<Long, List<Long>> treatments = treatmentToothRepository
                .findByToothRecordIdIn(recordIds).stream()
                .collect(Collectors.groupingBy(TreatmentTooth::getToothRecordId,
                        Collectors.mapping(TreatmentTooth::getTreatmentId, Collectors.toList())));
        List<ToothSurfaceRecord> surfaceRecords =
                toothSurfaceRecordRepository.findByToothRecordIdInOrderBySurfaceAsc(recordIds);
        Map<Long, List<ToothSurfaceRecord>> surfacesByRecord = surfaceRecords.stream()
                .collect(Collectors.groupingBy(ToothSurfaceRecord::getToothRecordId));
        Map<Long, List<ToothSurfaceHistory>> surfaceHistories = surfaceHistories(surfaceRecords);
        Map<Long, String> actorNames = actorNames(Stream.concat(
                histories.values().stream().flatMap(Collection::stream)
                        .map(ToothHistory::getActorUserId),
                surfaceHistories.values().stream().flatMap(Collection::stream)
                        .map(ToothSurfaceHistory::getActorUserId))
                .toList());
        return records.stream()
                .map(record -> toView(record,
                        histories.getOrDefault(record.getId(), List.of()),
                        treatments.getOrDefault(record.getId(), List.of()), actorNames,
                        surfacesByRecord.getOrDefault(record.getId(), List.of()), surfaceHistories))
                .toList();
    }

    /** @throws NotFoundException si el paciente o la pieza no existen (o la pieza no es del paciente) */
    @Transactional(readOnly = true)
    public ToothView get(Long patientId, int toothFdi, AuthenticatedUser actor) {
        assertReadAccess(patientId, actor);
        ToothRecord record = requireToothRecord(patientId, toothFdi);
        return view(record);
    }

    /**
     * Registra el estado inicial de una pieza. Cada pieza admite un único registro vigente.
     *
     * @throws ToothRecordAlreadyExistsException si la pieza ya tiene registro (409)
     */
    @Transactional
    public ToothView create(Long patientId, ToothData data, AuthenticatedUser actor) {
        return create(patientId, data, actor, null);
    }

    @Transactional
    public ToothView create(Long patientId, ToothData data, AuthenticatedUser actor, Selection selection) {
        requireWriter(actor);
        assertReadAccess(patientId, actor);
        RecordedAppointmentContext context = contexts.capture(patientId, selection, actor);
        if (toothRecordRepository.existsByPatientIdAndToothFdi(patientId, data.toothFdi())) {
            throw new ToothRecordAlreadyExistsException(
                    "La pieza " + data.toothFdi() + " ya tiene un registro; actualízala con PUT.");
        }
        Instant now = registrationInstant();
        ToothRecord record = toothRecordRepository.saveAndFlush(ToothRecord.create(
                patientId, data.toothFdi(), data.condition(), data.notes(), now));
        toothHistoryRepository.save(ToothHistory.create(
                record.getId(), null, record.getCondition(), actor.userId(), now, context));
        if (record.getNotes() != null) notesHistory.save(ToothNotesHistory.create(
                record.getId(), null, record.getNotes(), actor.userId(), now, context));
        replaceTreatments(patientId, record.getId(), data.treatmentIds(), actor, now, context);
        return view(record);
    }

    /** Actualiza la condición y observaciones de una pieza; cada cambio de condición deja historial. */
    @Transactional
    public ToothView update(Long patientId, int toothFdi, ToothData data, AuthenticatedUser actor) {
        return update(patientId, toothFdi, data, actor, null);
    }

    @Transactional
    public ToothView update(Long patientId, int toothFdi, ToothData data, AuthenticatedUser actor, Selection selection) {
        requireWriter(actor);
        assertReadAccess(patientId, actor);
        contexts.validateForRegistration(patientId, selection, actor);
        ToothRecord record = lockTooth(patientId, toothFdi);
        ToothCondition previous = record.getCondition();
        String previousNotes = record.getNotes();
        var candidate = ToothRecord.create(patientId, toothFdi, data.condition(), data.notes(), registrationInstant());
        boolean conditionChanged = previous != candidate.getCondition();
        boolean notesChanged = !Objects.equals(previousNotes, candidate.getNotes());
        var requested = requestedTreatments(patientId, data.treatmentIds());
        boolean linksChanged = !treatmentToothRepository.findByToothRecordId(record.getId()).stream()
                .map(TreatmentTooth::getTreatmentId).collect(Collectors.toSet()).equals(requested);
        if (!conditionChanged && !notesChanged && !linksChanged) return view(record);
        RecordedAppointmentContext context = contexts.capture(patientId, selection, actor);
        Instant now = registrationInstant();
        replaceTreatments(patientId, record.getId(), data.treatmentIds(), actor, now, context);
        record.update(candidate.getCondition(), candidate.getNotes(), now);
        if (conditionChanged) {
            toothHistoryRepository.save(ToothHistory.create(
                    record.getId(), previous, record.getCondition(), actor.userId(), now, context));
        }
        if (notesChanged) notesHistory.save(ToothNotesHistory.create(
                record.getId(), previousNotes, record.getNotes(), actor.userId(), now, context));
        return view(record);
    }

    /** Superficies registradas de una pieza, ordenadas por superficie. */
    @Transactional(readOnly = true)
    public List<ToothSurfaceView> listSurfaces(Long patientId, int toothFdi, AuthenticatedUser actor) {
        assertReadAccess(patientId, actor);
        ToothRecord record = requireToothRecord(patientId, toothFdi);
        List<ToothSurfaceRecord> surfaceRecords =
                toothSurfaceRecordRepository.findByToothRecordIdOrderBySurfaceAsc(record.getId());
        return toSurfaceViews(surfaceRecords);
    }

    /** @throws NotFoundException si el paciente, la pieza o la superficie no existen (o no es del paciente) */
    @Transactional(readOnly = true)
    public ToothSurfaceView getSurface(Long patientId, int toothFdi, ToothSurface surface,
                                       AuthenticatedUser actor) {
        assertReadAccess(patientId, actor);
        ToothRecord record = requireToothRecord(patientId, toothFdi);
        ToothSurfaceRecord surfaceRecord = requireSurfaceRecord(record.getId(), surface);
        return toSurfaceView(surfaceRecord);
    }

    /**
     * Registra el estado de una superficie de una pieza. Cada superficie admite un único registro
     * vigente y solo las superficies del grupo del diente son válidas (RF-OD-02).
     *
     * @throws ToothSurfaceAlreadyExistsException si la superficie ya tiene registro (409)
     */
    @Transactional
    public ToothSurfaceView createSurface(Long patientId, int toothFdi, SurfaceData data,
                                          AuthenticatedUser actor) {
        return createSurface(patientId, toothFdi, data, actor, null);
    }

    @Transactional
    public ToothSurfaceView createSurface(Long patientId, int toothFdi, SurfaceData data,
                                          AuthenticatedUser actor, Selection selection) {
        requireWriter(actor);
        assertReadAccess(patientId, actor);
        RecordedAppointmentContext context = contexts.capture(patientId, selection, actor);
        ToothRecord record = lockTooth(patientId, toothFdi);
        if (toothSurfaceRecordRepository.existsByToothRecordIdAndSurface(record.getId(), data.surface())) {
            throw new ToothSurfaceAlreadyExistsException(
                    "La superficie " + data.surface() + " de la pieza " + toothFdi
                            + " ya está registrada; actualízala con PUT.");
        }
        Instant now = registrationInstant();
        ToothSurfaceRecord surfaceRecord = toothSurfaceRecordRepository.saveAndFlush(ToothSurfaceRecord.create(
                record.getId(), record.getToothFdi(), data.surface(), data.condition(), data.notes(), now));
        toothSurfaceHistoryRepository.save(ToothSurfaceHistory.create(
                surfaceRecord.getId(), null, surfaceRecord.getCondition(), actor.userId(), now, context));
        if (surfaceRecord.getNotes() != null) surfaceNotesHistory.save(ToothSurfaceNotesHistory.create(
                surfaceRecord.getId(), null, surfaceRecord.getNotes(), actor.userId(), now, context));
        return toSurfaceView(surfaceRecord);
    }

    /** Actualiza la condición y observaciones de una superficie; cada cambio de condición deja historial. */
    @Transactional
    public ToothSurfaceView updateSurface(Long patientId, int toothFdi, ToothSurface surface,
                                          ToothCondition condition, String notes,
                                          AuthenticatedUser actor) {
        return updateSurface(patientId, toothFdi, surface, condition, notes, actor, null);
    }

    @Transactional
    public ToothSurfaceView updateSurface(Long patientId, int toothFdi, ToothSurface surface,
            ToothCondition condition, String notes, AuthenticatedUser actor, Selection selection) {
        requireWriter(actor);
        assertReadAccess(patientId, actor);
        contexts.validateForRegistration(patientId, selection, actor);
        ToothRecord record = lockTooth(patientId, toothFdi);
        ToothSurfaceRecord surfaceRecord = toothSurfaceRecordRepository.findForUpdate(record.getId(), surface)
                .orElseThrow(NotFoundException::new);
        entityManager.refresh(surfaceRecord, LockModeType.PESSIMISTIC_WRITE);
        ToothCondition previous = surfaceRecord.getCondition();
        String previousNotes = surfaceRecord.getNotes();
        var candidate = ToothSurfaceRecord.create(record.getId(), toothFdi, surface, condition, notes, registrationInstant());
        boolean conditionChanged = previous != candidate.getCondition();
        boolean notesChanged = !Objects.equals(previousNotes, candidate.getNotes());
        if (!conditionChanged && !notesChanged) return toSurfaceView(surfaceRecord);
        RecordedAppointmentContext context = contexts.capture(patientId, selection, actor);
        Instant now = registrationInstant();
        surfaceRecord.update(candidate.getCondition(), candidate.getNotes(), now);
        if (conditionChanged) {
            toothSurfaceHistoryRepository.save(ToothSurfaceHistory.create(
                    surfaceRecord.getId(), previous, surfaceRecord.getCondition(), actor.userId(), now, context));
        }
        if (notesChanged) surfaceNotesHistory.save(ToothSurfaceNotesHistory.create(
                surfaceRecord.getId(), previousNotes, surfaceRecord.getNotes(), actor.userId(), now, context));
        return toSurfaceView(surfaceRecord);
    }

    /**
     * El paciente solo alcanza su propio odontograma; un recurso ajeno responde 404 (§8.2). Los
     * roles clínicos (DENTIST/OWNER) no se limitan por propiedad.
     */
    private void assertReadAccess(Long patientId, AuthenticatedUser actor) {
        patientService.get(patientId);
        if (actor.role() == Role.PATIENT) {
            Long ownPatientId = patientService.findPatientIdForUser(actor.userId()).orElse(null);
            if (!Objects.equals(patientId, ownPatientId)) {
                throw new NotFoundException();
            }
        }
    }

    private ToothRecord requireToothRecord(Long patientId, int toothFdi) {
        return toothRecordRepository.findByPatientIdAndToothFdi(patientId, toothFdi)
                .orElseThrow(NotFoundException::new);
    }

    private ToothRecord lockTooth(Long patientId, int toothFdi) {
        var record = toothRecordRepository.findForUpdate(patientId, toothFdi).orElseThrow(NotFoundException::new);
        entityManager.refresh(record, LockModeType.PESSIMISTIC_WRITE);
        return record;
    }

    private static void requireWriter(AuthenticatedUser actor) {
        if (actor.role() != Role.OWNER && actor.role() != Role.DENTIST)
            throw new ForbiddenOperationException("No tienes permiso para registrar el odontograma.");
    }

    private Instant registrationInstant() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }

    private ToothSurfaceRecord requireSurfaceRecord(Long toothRecordId, ToothSurface surface) {
        return toothSurfaceRecordRepository.findByToothRecordIdAndSurface(toothRecordId, surface)
                .orElseThrow(NotFoundException::new);
    }

    /**
     * RF-OD-05: reemplaza los tratamientos vinculados a la pieza. Cada tratamiento debe existir y
     * pertenecer al mismo paciente; los duplicados se ignoran.
     */
    private boolean replaceTreatments(Long patientId, Long toothRecordId, List<Long> treatmentIds,
            AuthenticatedUser actor, Instant now, RecordedAppointmentContext context) {
        // Sole production writer: caller owns the transaction and the tooth lock (or its new row).
        var requested = requestedTreatments(patientId, treatmentIds);
        var existing = treatmentToothRepository.findByToothRecordId(toothRecordId);
        var previous = existing.stream().map(TreatmentTooth::getTreatmentId).collect(Collectors.toSet());
        if (previous.equals(requested)) return false;
        existing.stream().filter(link -> !requested.contains(link.getTreatmentId()))
                .sorted(java.util.Comparator.comparing(TreatmentTooth::getTreatmentId)).forEach(link -> {
                    treatmentToothRepository.delete(link);
                    treatmentHistory.save(ToothTreatmentHistory.create(toothRecordId, link.getTreatmentId(),
                            TreatmentLinkChange.REMOVE, actor.userId(), now, context));
                });
        requested.stream().filter(id -> !previous.contains(id)).forEach(id -> {
            treatmentToothRepository.save(TreatmentTooth.link(id, toothRecordId, now));
            treatmentHistory.save(ToothTreatmentHistory.create(toothRecordId, id,
                    TreatmentLinkChange.ADD, actor.userId(), now, context));
        });
        return true;
    }

    private TreeSet<Long> requestedTreatments(Long patientId, List<Long> ids) {
        var requested = new TreeSet<Long>();
        if (ids != null) ids.stream().filter(Objects::nonNull).forEach(requested::add);
        requested.forEach(id -> treatmentService.get(patientId, id));
        return requested;
    }

    private ToothView view(ToothRecord record) {
        List<ToothHistory> histories =
                toothHistoryRepository.findByToothRecordIdOrderByChangedAtAscIdAsc(record.getId());
        List<Long> treatments = treatmentToothRepository.findByToothRecordId(record.getId()).stream()
                .map(TreatmentTooth::getTreatmentId).toList();
        List<ToothSurfaceRecord> surfaceRecords =
                toothSurfaceRecordRepository.findByToothRecordIdOrderBySurfaceAsc(record.getId());
        Map<Long, List<ToothSurfaceHistory>> surfaceHistories = surfaceHistories(surfaceRecords);
        Map<Long, String> actorNames = actorNames(Stream.concat(
                histories.stream().map(ToothHistory::getActorUserId),
                surfaceHistories.values().stream().flatMap(Collection::stream)
                        .map(ToothSurfaceHistory::getActorUserId))
                .toList());
        return toView(record, histories, treatments, actorNames, surfaceRecords, surfaceHistories);
    }

    private Map<Long, List<ToothSurfaceHistory>> surfaceHistories(
            List<ToothSurfaceRecord> surfaceRecords) {
        if (surfaceRecords.isEmpty()) {
            return Map.of();
        }
        return toothSurfaceHistoryRepository.findByToothSurfaceRecordIdInOrderByChangedAtAscIdAsc(
                        surfaceRecords.stream().map(ToothSurfaceRecord::getId).toList()).stream()
                .collect(Collectors.groupingBy(ToothSurfaceHistory::getToothSurfaceRecordId));
    }

    private List<ToothSurfaceView> toSurfaceViews(List<ToothSurfaceRecord> surfaceRecords) {
        Map<Long, List<ToothSurfaceHistory>> surfaceHistories = surfaceHistories(surfaceRecords);
        Map<Long, String> actorNames = actorNames(surfaceHistories.values().stream()
                .flatMap(Collection::stream).map(ToothSurfaceHistory::getActorUserId).toList());
        return surfaceRecords.stream()
                .map(record -> toSurfaceView(record,
                        surfaceHistories.getOrDefault(record.getId(), List.of()), actorNames))
                .toList();
    }

    private ToothSurfaceView toSurfaceView(ToothSurfaceRecord record) {
        List<ToothSurfaceHistory> histories =
                toothSurfaceHistoryRepository.findByToothSurfaceRecordIdOrderByChangedAtAscIdAsc(
                        record.getId());
        Map<Long, String> actorNames = actorNames(
                histories.stream().map(ToothSurfaceHistory::getActorUserId).toList());
        return toSurfaceView(record, histories, actorNames);
    }

    private static ToothSurfaceView toSurfaceView(ToothSurfaceRecord record,
                                                  List<ToothSurfaceHistory> histories,
                                                  Map<Long, String> actorNames) {
        List<ToothSurfaceHistoryView> historyViews = histories.stream()
                .map(history -> new ToothSurfaceHistoryView(history.getId(),
                        history.getPreviousCondition(), history.getNewCondition(),
                         history.getActorUserId(), actorNames.getOrDefault(history.getActorUserId(), "Usuario #" + history.getActorUserId()),
                        history.getChangedAt()))
                .toList();
        return new ToothSurfaceView(record.getId(), record.getToothRecordId(), record.getSurface(),
                record.getCondition(), record.getNotes(), record.getCreatedAt(), record.getUpdatedAt(),
                historyViews);
    }

    private static ToothView toView(ToothRecord record, List<ToothHistory> histories,
                                    List<Long> treatmentIds, Map<Long, String> actorNames,
                                    List<ToothSurfaceRecord> surfaceRecords,
                                    Map<Long, List<ToothSurfaceHistory>> surfaceHistories) {
        List<ToothHistoryView> historyViews = histories.stream()
                .map(history -> new ToothHistoryView(history.getId(), history.getPreviousCondition(),
                        history.getNewCondition(), history.getActorUserId(),
                        actorNames.getOrDefault(history.getActorUserId(), "Usuario #" + history.getActorUserId()), history.getChangedAt()))
                .toList();
        List<ToothSurfaceView> surfaceViews = surfaceRecords.stream()
                .map(surface -> toSurfaceView(surface,
                        surfaceHistories.getOrDefault(surface.getId(), List.of()), actorNames))
                .toList();
        return new ToothView(record, historyViews, treatmentIds, surfaceViews);
    }

    private Map<Long, String> actorNames(Collection<Long> userIds) {
        return dentistService.profilesByUserIds(userIds).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> fullName(entry.getValue())));
    }

    private static String fullName(DentistService.DentistProfile profile) {
        return (profile.firstName() + " " + profile.lastName()).strip();
    }

    /** Contenido editable de una pieza. */
    public record ToothData(int toothFdi, ToothCondition condition, String notes,
                            List<Long> treatmentIds) {
    }

    /** Contenido editable de una superficie. */
    public record SurfaceData(ToothSurface surface, ToothCondition condition, String notes) {
    }

    /** Pieza con su historial, tratamientos vinculados y superficies. */
    public record ToothView(ToothRecord record, List<ToothHistoryView> history,
                            List<Long> treatmentIds, List<ToothSurfaceView> surfaces) {
    }

    /** Entrada de historial con el nombre del autor resuelto. */
    public record ToothHistoryView(Long id, ToothCondition previousCondition,
                                   ToothCondition newCondition, Long actorUserId, String actorName,
                                   Instant changedAt) {
    }

    /** Superficie con su historial. */
    public record ToothSurfaceView(Long id, Long toothRecordId, ToothSurface surface,
                                   ToothCondition condition, String notes, Instant createdAt,
                                   Instant updatedAt, List<ToothSurfaceHistoryView> history) {
    }

    /** Entrada de historial de superficie con el nombre del autor resuelto. */
    public record ToothSurfaceHistoryView(Long id, ToothCondition previousCondition,
                                          ToothCondition newCondition, Long actorUserId,
                                          String actorName, Instant changedAt) {
    }
}

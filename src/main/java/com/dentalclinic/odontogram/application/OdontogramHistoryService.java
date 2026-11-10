package com.dentalclinic.odontogram.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.dentalclinic.appointment.application.AppointmentClinicalContextService;
import com.dentalclinic.appointment.application.AppointmentClinicalContextService.*;
import com.dentalclinic.appointment.domain.RecordedAppointmentContext;
import com.dentalclinic.clinic.application.DentistService;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.odontogram.domain.*;
import com.dentalclinic.odontogram.infrastructure.repository.*;
import com.dentalclinic.patient.application.PatientService;

@Service
public class OdontogramHistoryService {
    private final OdontogramEventRepository events;
    private final ToothRecordRepository teeth;
    private final ToothSurfaceRecordRepository surfaces;
    private final AppointmentClinicalContextService contexts;
    private final PatientService patients;
    private final DentistService dentists;
    private final OdontogramService current;

    public OdontogramHistoryService(OdontogramEventRepository events, ToothRecordRepository teeth,
            ToothSurfaceRecordRepository surfaces, AppointmentClinicalContextService contexts,
            PatientService patients, DentistService dentists, OdontogramService current) {
        this.events = events; this.teeth = teeth; this.surfaces = surfaces; this.contexts = contexts;
        this.patients = patients; this.dentists = dentists; this.current = current;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<EventView> history(Long patientId, Integer toothFdi, Instant from, Instant to,
            Long appointmentId, Integer occurrenceVersion, int page, int size, AuthenticatedUser actor) {
        requireClinical(actor);
        patients.get(patientId);
        validatePage(page, size);
        if (toothFdi != null && !ToothNotation.isValid(toothFdi)) invalid("La pieza FDI no es válida.");
        if (from != null && to != null && from.isAfter(to)) invalid("El inicio del filtro es posterior al final.");
        var selection = selection(appointmentId, occurrenceVersion);
        if (selection != null) contexts.read(patientId, selection, actor);
        return page(new OdontogramEventRepository.Filter(patientId, toothFdi, from, to, appointmentId, occurrenceVersion), page, size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AtView at(Long patientId, Instant at, Long appointmentId, Integer occurrenceVersion,
            int page, int size, AuthenticatedUser actor) {
        requireClinical(actor);
        patients.get(patientId);
        validatePage(page, size);
        var selection = selection(appointmentId, occurrenceVersion);
        if ((at == null) == (selection == null)) invalid("Elige un instante o una cita/ocurrencia, no ambos.");
        if (at != null) return new AtView(null, reconstruct(patientId, at), null, null);
        var occurrence = contexts.read(patientId, selection, actor);
        var snapshot = occurrence.kind() == ContextKind.FROZEN ? reconstruct(patientId, occurrence.capturedAt()) : null;
        var attributed = page(new OdontogramEventRepository.Filter(patientId, null, null, null,
                appointmentId, occurrenceVersion), page, size);
        return new AtView(occurrence, snapshot, attributed, current.list(patientId, actor));
    }

    private OdontogramReconstruction.Snapshot reconstruct(Long patientId, Instant at) {
        var records = teeth.findByPatientIdOrderByToothFdiAsc(patientId);
        var metadata = records.stream().map(t -> new OdontogramReconstruction.ToothMetadata(t.getId(), t.getToothFdi(), t.getCreatedAt())).toList();
        var surfaceMetadata = records.isEmpty() ? List.<OdontogramReconstruction.SurfaceMetadata>of()
                : surfaces.findByToothRecordIdInOrderBySurfaceAsc(records.stream().map(ToothRecord::getId).toList()).stream()
                .map(s -> new OdontogramReconstruction.SurfaceMetadata(s.getId(), s.getToothRecordId(), s.getSurface(), s.getCreatedAt())).toList();
        return OdontogramReconstruction.at(at, metadata, surfaceMetadata, events.until(patientId, at));
    }

    private Page<EventView> page(OdontogramEventRepository.Filter filter, int page, int size) {
        var result = events.page(filter, PageRequest.of(page, size));
        Map<Long, String> names = dentists.profilesByUserIds(result.getContent().stream()
                .map(OdontogramHistoryEvent::actorUserId).distinct().toList()).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> (e.getValue().firstName() + " " + e.getValue().lastName()).strip()));
        return result.map(e -> new EventView(e.source().kind(), e.source(), e.id(), e.toothFdi(), e.surface(),
                e.previousCondition(), e.newCondition(), e.previousNotes(), e.newNotes(), e.treatmentId(), e.changeKind(),
                e.actorUserId(), names.getOrDefault(e.actorUserId(), "Usuario #" + e.actorUserId()), e.changedAt(), e.occurrence()));
    }
    private static void requireClinical(AuthenticatedUser actor) {
        if (actor.role() != Role.OWNER && actor.role() != Role.DENTIST)
            throw new ForbiddenOperationException("No tienes acceso al historial dental.");
    }
    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) invalid("Revisa la página y el tamaño (1–100).");
    }
    private static Selection selection(Long appointment, Integer version) {
        if (appointment == null && version == null) return null;
        if (appointment == null || version == null || appointment < 1 || version < 1)
            invalid("Cita y versión de ocurrencia deben estar completas y ser positivas.");
        return new Selection(appointment, version);
    }
    private static void invalid(String message) { throw new InvalidToothException(message); }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record EventView(OdontogramHistoryEvent.Kind kind, OdontogramHistoryEvent.Source source, Long id,
            int toothFdi, ToothSurface surface, ToothCondition previousCondition, ToothCondition newCondition,
            String previousNotes, String newNotes, Long treatmentId, TreatmentLinkChange changeKind,
            Long actorUserId, String actorName, Instant changedAt, RecordedAppointmentContext occurrence) { }
    public record AtView(OccurrenceView occurrence, OdontogramReconstruction.Snapshot snapshot,
            Page<EventView> attributedEvents, List<OdontogramService.ToothView> currentState) { }
}

package com.dentalclinic.clinical.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.dentalclinic.appointment.application.AppointmentClinicalContextService;
import com.dentalclinic.appointment.application.AppointmentClinicalContextService.OccurrenceView;
import com.dentalclinic.appointment.application.AppointmentClinicalContextService.Selection;
import com.dentalclinic.clinical.domain.ClinicalNote;
import com.dentalclinic.clinical.domain.TreatmentEvolution;
import com.dentalclinic.clinical.infrastructure.repository.ClinicalNoteRepository;
import com.dentalclinic.clinical.infrastructure.repository.TreatmentEvolutionRepository;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

@Service
public class ClinicalTraceService {
    private final AppointmentClinicalContextService contexts;
    private final ClinicalNoteRepository notes;
    private final TreatmentEvolutionRepository evolutions;
    private final ClinicalService clinical;
    private final TreatmentEvolutionService treatments;

    public ClinicalTraceService(AppointmentClinicalContextService contexts, ClinicalNoteRepository notes,
            TreatmentEvolutionRepository evolutions, ClinicalService clinical, TreatmentEvolutionService treatments) {
        this.contexts = contexts; this.notes = notes; this.evolutions = evolutions;
        this.clinical = clinical; this.treatments = treatments;
    }

    /**
     * Count, page and contextual header observe one PostgreSQL snapshot; no writes or locks on GET.
     * La paginacion cuenta REGISTROS ORIGINALES (raices), no correcciones: cada item expone la cadena
     * completa ({@code originalEntry}, {@code corrections}, {@code currentEntry}) de forma acotada y
     * las correcciones de la pagina se cargan en una sola consulta.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TraceView read(Long patientId, Selection selection, Section section, int page, int size, AuthenticatedUser actor) {
        OccurrenceView context = contexts.read(patientId, selection, actor);
        var pageable = PageRequest.of(page, size, Sort.by("createdAt", "id"));
        Page<? extends Entry> results = switch (section) {
            case NOTES -> {
                Page<ClinicalNote> roots = notes.findAttention(patientId, selection.appointmentId(),
                        selection.occurrenceVersion(), pageable);
                List<NoteEntry> entries = noteEntries(roots.getContent());
                yield new PageImpl<Entry>(new ArrayList<>(entries), pageable, roots.getTotalElements());
            }
            case EVOLUTIONS -> {
                Page<TreatmentEvolution> roots = evolutions.findAttention(patientId, selection.appointmentId(),
                        selection.occurrenceVersion(), pageable);
                List<EvolutionEntry> entries = evolutionEntries(roots.getContent());
                yield new PageImpl<Entry>(new ArrayList<>(entries), pageable, roots.getTotalElements());
            }
        };
        return new TraceView(context, section, results);
    }

    /** Ensambla cadenas de notas (raiz + correcciones) con autores resueltos en lote. */
    public List<NoteEntry> noteEntries(List<ClinicalNote> roots) {
        Map<Long, List<ClinicalNote>> correctionsByRoot = correctionsByNote(roots);
        List<ClinicalNote> all = flatten(roots, correctionsByRoot);
        Map<Long, String> names = clinical.authorNames(authorIds(all));
        return roots.stream().map(root -> {
            List<ClinicalService.ClinicalNoteView> chain = correctionsByRoot
                    .getOrDefault(root.getId(), List.of()).stream()
                    .map(c -> clinical.toView(c, name(names, c.getAuthorDentistId()))).toList();
            ClinicalService.ClinicalNoteView original = clinical.toView(root, name(names, root.getAuthorDentistId()));
            ClinicalService.ClinicalNoteView current = chain.isEmpty() ? original : chain.get(chain.size() - 1);
            return new NoteEntry(root.getId(), original, chain, current);
        }).toList();
    }

    public List<EvolutionEntry> evolutionEntries(List<TreatmentEvolution> roots) {
        Map<Long, List<TreatmentEvolution>> correctionsByRoot = correctionsByEvolution(roots);
        List<TreatmentEvolution> all = flattenEvolutions(roots, correctionsByRoot);
        Map<Long, String> names = treatments.authorNames(authorIdsEvolutions(all));
        return roots.stream().map(root -> {
            List<TreatmentEvolutionService.TreatmentEvolutionView> chain = correctionsByRoot
                    .getOrDefault(root.getId(), List.of()).stream()
                    .map(c -> treatments.toView(c, name(names, c.getAuthorDentistId()))).toList();
            TreatmentEvolutionService.TreatmentEvolutionView original =
                    treatments.toView(root, name(names, root.getAuthorDentistId()));
            TreatmentEvolutionService.TreatmentEvolutionView current = chain.isEmpty() ? original : chain.get(chain.size() - 1);
            return new EvolutionEntry(root.getId(), original, chain, current);
        }).toList();
    }

    private Map<Long, List<ClinicalNote>> correctionsByNote(List<ClinicalNote> roots) {
        List<Long> ids = roots.stream().map(ClinicalNote::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return notes.findByCorrectsNoteIdInOrderByCreatedAtAscIdAsc(ids).stream()
                .collect(Collectors.groupingBy(ClinicalNote::getCorrectsNoteId, LinkedHashMap::new, Collectors.toList()));
    }

    private Map<Long, List<TreatmentEvolution>> correctionsByEvolution(List<TreatmentEvolution> roots) {
        List<Long> ids = roots.stream().map(TreatmentEvolution::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return evolutions.findByCorrectsEvolutionIdInOrderByCreatedAtAscIdAsc(ids).stream()
                .collect(Collectors.groupingBy(TreatmentEvolution::getCorrectsEvolutionId, LinkedHashMap::new, Collectors.toList()));
    }

    private static List<ClinicalNote> flatten(List<ClinicalNote> roots, Map<Long, List<ClinicalNote>> corrections) {
        List<ClinicalNote> all = new ArrayList<>(roots);
        corrections.values().forEach(all::addAll);
        return all;
    }

    private static List<TreatmentEvolution> flattenEvolutions(List<TreatmentEvolution> roots,
            Map<Long, List<TreatmentEvolution>> corrections) {
        List<TreatmentEvolution> all = new ArrayList<>(roots);
        corrections.values().forEach(all::addAll);
        return all;
    }

    private static List<Long> authorIds(List<ClinicalNote> notes) {
        return notes.stream().map(ClinicalNote::getAuthorDentistId).distinct().toList();
    }

    private static List<Long> authorIdsEvolutions(List<TreatmentEvolution> entries) {
        return entries.stream().map(TreatmentEvolution::getAuthorDentistId).distinct().toList();
    }

    private static String name(Map<Long, String> names, Long dentistId) {
        return names.getOrDefault(dentistId, "Autor desconocido");
    }

    public enum Section { NOTES, EVOLUTIONS }
    public sealed interface Entry permits NoteEntry, EvolutionEntry { Long recordId(); }
    public record NoteEntry(Long recordId, ClinicalService.ClinicalNoteView originalEntry,
            List<ClinicalService.ClinicalNoteView> corrections,
            ClinicalService.ClinicalNoteView currentEntry) implements Entry { }
    public record EvolutionEntry(Long recordId, TreatmentEvolutionService.TreatmentEvolutionView originalEntry,
            List<TreatmentEvolutionService.TreatmentEvolutionView> corrections,
            TreatmentEvolutionService.TreatmentEvolutionView currentEntry) implements Entry { }
    public record TraceView(OccurrenceView context, Section section, Page<? extends Entry> results) { }
}

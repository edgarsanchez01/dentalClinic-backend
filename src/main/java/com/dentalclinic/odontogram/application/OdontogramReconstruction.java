package com.dentalclinic.odontogram.application;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.dentalclinic.odontogram.domain.*;
import com.dentalclinic.odontogram.domain.OdontogramHistoryEvent.Source;

/** Pure fold. Current mutable values and previous_* are never projected into missing history. */
public final class OdontogramReconstruction {
    private OdontogramReconstruction() { }
    public enum Knowledge { KNOWN, UNKNOWN, PARTIAL }
    public enum Registration { REGISTERED, NOT_REGISTERED, UNKNOWN }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Value<T>(Knowledge knowledge, T value, Instant knownFrom) {
        static <T> Value<T> unknown() { return new Value<>(Knowledge.UNKNOWN, null, null); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Treatments(Knowledge knowledge, List<Long> treatmentIds, List<Long> knownLinkedIds, List<Long> knownUnlinkedIds) { }
    public record ToothMetadata(Long id, int toothFdi, Instant createdAt) { }
    public record SurfaceMetadata(Long id, Long toothRecordId, ToothSurface surface, Instant createdAt) { }
    public record SurfaceAt(ToothSurface surface, Registration registration, Value<ToothCondition> condition, Value<String> notes) { }
    public record ToothAt(int toothFdi, Dentition dentition, Registration registration, Value<ToothCondition> condition,
            Value<String> notes, Treatments treatments, List<SurfaceAt> surfaces) { }
    public enum CompletenessStatus { COMPLETE, PARTIAL, UNKNOWN }
    public record Completeness(CompletenessStatus status, List<String> reasons) { }
    public record Snapshot(Instant at, List<ToothAt> teeth, Completeness completeness) { }

    public static Snapshot at(Instant at, List<ToothMetadata> teeth, List<SurfaceMetadata> surfaces,
            List<OdontogramHistoryEvent> history) {
        var events = history.stream().filter(e -> !e.changedAt().isAfter(at)).sorted(OdontogramHistoryEvent.ORDER).toList();
        var byTooth = events.stream().collect(Collectors.groupingBy(OdontogramHistoryEvent::toothRecordId));
        var bySurface = events.stream().filter(e -> e.surfaceRecordId() != null)
                .collect(Collectors.groupingBy(OdontogramHistoryEvent::surfaceRecordId));
        var surfacesByTooth = surfaces.stream().collect(Collectors.groupingBy(SurfaceMetadata::toothRecordId));
        List<ToothAt> states = new ArrayList<>();
        var reasons = new TreeSet<String>();
        boolean someKnown = false;
        for (var tooth : teeth.stream().sorted(Comparator.comparingInt(ToothMetadata::toothFdi)).toList()) {
            Registration registration = registration(tooth.createdAt(), at);
            var stream = byTooth.getOrDefault(tooth.id(), List.of());
            Value<ToothCondition> condition = latestCondition(stream, Source.CONDITION_TOOTH);
            Value<String> notes = latestNotes(stream, Source.NOTES_TOOTH);
            var linked = new TreeSet<Long>();
            var unlinked = new TreeSet<Long>();
            for (var event : stream) if (event.source() == Source.TREATMENT_LINK) {
                if (event.changeKind() == TreatmentLinkChange.ADD) { linked.add(event.treatmentId()); unlinked.remove(event.treatmentId()); }
                else { unlinked.add(event.treatmentId()); linked.remove(event.treatmentId()); }
            }
            var treatmentKnowledge = linked.isEmpty() && unlinked.isEmpty() ? Knowledge.UNKNOWN : Knowledge.PARTIAL;
            // V015 has no complete-set/cutover marker: even a first ADD does not prove all other links absent.
            var treatments = new Treatments(treatmentKnowledge, null, List.copyOf(linked), List.copyOf(unlinked));
            List<SurfaceAt> surfaceStates = new ArrayList<>();
            for (var surface : surfacesByTooth.getOrDefault(tooth.id(), List.of()).stream()
                    .sorted(Comparator.comparing(s -> s.surface().name())).toList()) {
                var ss = bySurface.getOrDefault(surface.id(), List.of());
                var sc = latestCondition(ss, Source.CONDITION_SURFACE);
                var sn = latestNotes(ss, Source.NOTES_SURFACE);
                var sr = registration(surface.createdAt(), at);
                surfaceStates.add(new SurfaceAt(surface.surface(), sr, sc, sn));
                if (sr != Registration.NOT_REGISTERED) {
                    if (sc.knowledge() == Knowledge.UNKNOWN) reasons.add("SURFACE_CONDITION_UNKNOWN");
                    if (sn.knowledge() == Knowledge.UNKNOWN) reasons.add("SURFACE_NOTES_UNKNOWN");
                    someKnown |= sc.knowledge() == Knowledge.KNOWN || sn.knowledge() == Knowledge.KNOWN;
                }
            }
            if (registration != Registration.NOT_REGISTERED) {
                if (condition.knowledge() == Knowledge.UNKNOWN) reasons.add("CONDITION_UNKNOWN");
                if (notes.knowledge() == Knowledge.UNKNOWN) reasons.add("NOTES_UNKNOWN");
                reasons.add(treatmentKnowledge == Knowledge.UNKNOWN ? "TREATMENT_LINKS_UNKNOWN" : "TREATMENT_LINKS_PARTIAL");
                someKnown |= condition.knowledge() == Knowledge.KNOWN || notes.knowledge() == Knowledge.KNOWN
                        || treatmentKnowledge == Knowledge.PARTIAL;
            }
            states.add(new ToothAt(tooth.toothFdi(), ToothNotation.dentitionOf(tooth.toothFdi()), registration,
                    condition, notes, treatments, List.copyOf(surfaceStates)));
        }
        var completeness = reasons.isEmpty() ? CompletenessStatus.COMPLETE
                : someKnown ? CompletenessStatus.PARTIAL : CompletenessStatus.UNKNOWN;
        return new Snapshot(at, List.copyOf(states), new Completeness(completeness, List.copyOf(reasons)));
    }

    private static Registration registration(Instant created, Instant at) {
        return created == null ? Registration.UNKNOWN : created.isAfter(at) ? Registration.NOT_REGISTERED : Registration.REGISTERED;
    }
    private static Value<ToothCondition> latestCondition(List<OdontogramHistoryEvent> events, Source source) {
        Value<ToothCondition> value = Value.unknown();
        for (var event : events) if (event.source() == source)
            value = new Value<>(Knowledge.KNOWN, event.newCondition(), event.changedAt());
        return value;
    }
    private static Value<String> latestNotes(List<OdontogramHistoryEvent> events, Source source) {
        Value<String> value = Value.unknown();
        for (var event : events) if (event.source() == source)
            value = new Value<>(Knowledge.KNOWN, event.newNotes(), event.changedAt());
        return value;
    }
}

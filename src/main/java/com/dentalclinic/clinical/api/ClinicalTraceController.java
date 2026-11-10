package com.dentalclinic.clinical.api;

import java.util.List;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.dentalclinic.appointment.application.AppointmentClinicalContextService.OccurrenceView;
import com.dentalclinic.appointment.application.AppointmentClinicalContextService.Selection;
import com.dentalclinic.clinical.application.ClinicalTraceService;
import com.dentalclinic.clinical.application.ClinicalTraceService.Section;
import com.dentalclinic.clinical.api.dto.ClinicalNoteResponse;
import com.dentalclinic.clinical.api.dto.TreatmentEvolutionResponse;
import com.dentalclinic.identity.infrastructure.security.CurrentUser;
import com.dentalclinic.patient.api.dto.PageResponse;

@RestController
@Validated
@RequestMapping("/api/patients/{patientId}/clinical/appointments/{appointmentId}/trace")
public class ClinicalTraceController {
    private final ClinicalTraceService trace;
    public ClinicalTraceController(ClinicalTraceService trace) { this.trace = trace; }

    @GetMapping
    public TraceResponse get(@PathVariable @Positive Long patientId, @PathVariable @Positive Long appointmentId,
            @RequestParam @Positive int occurrenceVersion, @RequestParam Section section,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var view = trace.read(patientId, new Selection(appointmentId, occurrenceVersion), section, page, size, CurrentUser.require());
        List<TraceItem> items = view.results().getContent().stream().map(entry -> switch (entry) {
            case ClinicalTraceService.NoteEntry note -> (TraceItem) new NoteItem(note.recordId(),
                    ClinicalController.toDto(note.originalEntry()),
                    note.corrections().stream().map(ClinicalController::toDto).toList(),
                    ClinicalController.toDto(note.currentEntry()));
            case ClinicalTraceService.EvolutionEntry evolution -> new EvolutionItem(evolution.recordId(),
                    TreatmentEvolutionController.toDto(evolution.originalEntry()),
                    evolution.corrections().stream().map(TreatmentEvolutionController::toDto).toList(),
                    TreatmentEvolutionController.toDto(evolution.currentEntry()));
        }).toList();
        var results = view.results();
        return new TraceResponse(view.context(), List.of(Section.NOTES, Section.EVOLUTIONS), section,
                new PageResponse<>(items, results.getNumber(), results.getSize(), results.getTotalElements(), results.getTotalPages()));
    }

    public sealed interface TraceItem permits NoteItem, EvolutionItem { Long recordId(); }
    public record NoteItem(Long recordId, ClinicalNoteResponse originalEntry,
            List<ClinicalNoteResponse> corrections, ClinicalNoteResponse currentEntry) implements TraceItem { }
    public record EvolutionItem(Long recordId, TreatmentEvolutionResponse originalEntry,
            List<TreatmentEvolutionResponse> corrections, TreatmentEvolutionResponse currentEntry) implements TraceItem { }
    public record TraceResponse(OccurrenceView context, List<Section> availableSections, Section section, PageResponse<TraceItem> results) { }
}

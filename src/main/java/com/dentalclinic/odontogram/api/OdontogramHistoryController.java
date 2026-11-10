package com.dentalclinic.odontogram.api;

import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.dentalclinic.appointment.application.AppointmentClinicalContextService.OccurrenceView;
import com.dentalclinic.identity.infrastructure.security.CurrentUser;
import com.dentalclinic.odontogram.application.OdontogramHistoryService;
import com.dentalclinic.odontogram.application.OdontogramHistoryService.EventView;
import com.dentalclinic.odontogram.application.OdontogramReconstruction.*;
import com.dentalclinic.odontogram.api.dto.ToothRecordResponse;
import com.dentalclinic.patient.api.dto.PageResponse;

@RestController
@RequestMapping("/api/patients/{id}/odontogram")
public class OdontogramHistoryController {
    private final OdontogramHistoryService history;
    public OdontogramHistoryController(OdontogramHistoryService history) { this.history = history; }

    @GetMapping("/history")
    public PageResponse<EventView> history(@PathVariable("id") Long patientId,
            @RequestParam(required = false) Integer toothFdi, @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to, @RequestParam(required = false) Long appointmentId,
            @RequestParam(required = false) Integer occurrenceVersion, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(history.history(patientId, toothFdi, from, to, appointmentId,
                occurrenceVersion, page, size, CurrentUser.require()));
    }

    @GetMapping("/at")
    public Object at(@PathVariable("id") Long patientId, @RequestParam(required = false) Instant at,
            @RequestParam(required = false) Long appointmentId, @RequestParam(required = false) Integer occurrenceVersion,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var view = history.at(patientId, at, appointmentId, occurrenceVersion, page, size, CurrentUser.require());
        if (view.occurrence() == null) return new InstantResponse(new InstantReference("INSTANT", at), true,
                view.snapshot().teeth(), view.snapshot().completeness());
        return new AttentionResponse(new AttentionReference("ATTENTION", view.occurrence()), view.snapshot() != null,
                view.snapshot(), PageResponse.from(view.attributedEvents()), new CurrentState(view.currentState().stream()
                        .map(ToothRecordResponse::from).toList()));
    }

    public record InstantReference(String mode, Instant at) { }
    public record AttentionReference(String mode, OccurrenceView occurrence) { }
    public record InstantResponse(InstantReference reference, boolean reconstructionAvailable, List<ToothAt> teeth, Completeness completeness) { }
    public record CurrentState(List<ToothRecordResponse> teeth) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AttentionResponse(AttentionReference reference, boolean reconstructionAvailable,
            Snapshot stateAtCapture, PageResponse<EventView> attributedEvents, CurrentState currentState) { }
}

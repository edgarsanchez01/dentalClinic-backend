package com.dentalclinic.odontogram.api;

import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import com.dentalclinic.identity.infrastructure.security.CurrentUser;
import com.dentalclinic.odontogram.application.ReceptionDentalSummaryService;
import com.dentalclinic.odontogram.application.ReceptionDentalSummaryService.ToothSummary;

/** Dedicated read route; the HTTP matrix and service independently authorize the actor. */
@RestController
public class ReceptionDentalSummaryController {
    private final ReceptionDentalSummaryService summaries;

    public ReceptionDentalSummaryController(ReceptionDentalSummaryService summaries) {
        this.summaries = summaries;
    }

    @GetMapping("/api/patients/{id}/odontogram/reception-summary")
    public ResponseEntity<List<ToothSummary>> read(@PathVariable("id") Long patientId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(summaries.read(patientId, CurrentUser.require()));
    }
}

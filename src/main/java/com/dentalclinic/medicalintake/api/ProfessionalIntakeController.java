package com.dentalclinic.medicalintake.api;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.dentalclinic.identity.infrastructure.security.CurrentUser;
import com.dentalclinic.medicalintake.application.*;

@RestController
@RequestMapping("/api/patients/{patientId}/clinical/medical-intake/declarations")
@PreAuthorize("@authorization.isClinicalStaff(principal)")
public class ProfessionalIntakeController {
    private final MedicalIntakeService intake;
    public ProfessionalIntakeController(MedicalIntakeService intake) { this.intake = intake; }

    @GetMapping
    public ResponseEntity<Page<IntakeViews.Declaration>> history(@PathVariable Long patientId,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return MedicalIntakeController.sensitive(intake.professionalDeclarations(CurrentUser.require(), patientId, page, size));
    }
}

package com.dentalclinic.medicalintake.api;

import java.util.List;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.dentalclinic.identity.infrastructure.security.CurrentUser;
import com.dentalclinic.medicalintake.application.*;
import com.dentalclinic.medicalintake.domain.IntakeAnswers.Answer;
import static com.dentalclinic.medicalintake.application.IntakeDefinition.*;

@RestController
@RequestMapping("/api/patients/me/medical-intake")
@PreAuthorize("@authorization.isPatient(principal)")
public class MedicalIntakeController {
    private final MedicalIntakeService intake;
    public MedicalIntakeController(MedicalIntakeService intake) { this.intake = intake; }

    @GetMapping("/definition")
    public ResponseEntity<Definition> definition() {
        return sensitive(new Definition(QUESTIONNAIRE_VERSION, List.of(Answer.values()), NOTICE_VERSION,
                NOTICE_TEXT, hash(NOTICE_TEXT), DECLARATION_VERSION, DECLARATION_TEXT, true, true));
    }
    @GetMapping("/status")
    public ResponseEntity<IntakeViews.Status> status() { return sensitive(intake.status(CurrentUser.require())); }
    @GetMapping("/draft")
    public ResponseEntity<IntakeViews.Draft> draft() { return sensitive(intake.draft(CurrentUser.require())); }
    @PutMapping("/draft/personal")
    public ResponseEntity<IntakeViews.Draft> personal(@Valid @RequestBody IntakeCommands.Personal command) {
        return sensitive(intake.savePersonal(CurrentUser.require(), command));
    }
    @PostMapping("/privacy-acknowledgements")
    public ResponseEntity<IntakeViews.Draft> privacy(@Valid @RequestBody IntakeCommands.Privacy command) {
        return sensitive(intake.acknowledgePrivacy(CurrentUser.require(), command));
    }
    @PutMapping("/draft/medical")
    public ResponseEntity<IntakeViews.Draft> medical(@Valid @RequestBody IntakeCommands.MedicalSave command) {
        return sensitive(intake.saveMedical(CurrentUser.require(), command));
    }
    @PutMapping("/draft/dental")
    public ResponseEntity<IntakeViews.Draft> dental(@Valid @RequestBody IntakeCommands.DentalSave command) {
        return sensitive(intake.saveDental(CurrentUser.require(), command));
    }
    @PostMapping("/complete")
    public ResponseEntity<IntakeViews.ConfirmationResult> complete(@Valid @RequestBody IntakeCommands.Confirmation command) {
        return sensitive(intake.confirm(CurrentUser.require(), command, false));
    }
    @PostMapping("/revisions")
    public ResponseEntity<IntakeViews.ConfirmationResult> revise(@Valid @RequestBody IntakeCommands.Confirmation command) {
        return sensitive(intake.confirm(CurrentUser.require(), command, true));
    }
    @GetMapping("/declarations/latest")
    public ResponseEntity<IntakeViews.Declaration> latest() {
        return intake.latest(CurrentUser.require()).map(MedicalIntakeController::sensitive)
                .orElseGet(() -> ResponseEntity.noContent().header("Cache-Control", "no-store").build());
    }
    @GetMapping("/declarations")
    public ResponseEntity<Page<IntakeViews.Declaration>> history(@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size) {
        return sensitive(intake.ownDeclarations(CurrentUser.require(), page, size));
    }
    static <T> ResponseEntity<T> sensitive(T body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }
    public record Definition(String questionnaireVersion, List<Answer> answerOptions, String noticeVersion,
            String noticeText, String noticeHash, String declarationVersion, String declarationText,
            boolean legalReviewPending, boolean dentalReviewPending) { }
}

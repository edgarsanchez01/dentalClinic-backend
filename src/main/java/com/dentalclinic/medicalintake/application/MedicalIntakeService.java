package com.dentalclinic.medicalintake.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dentalclinic.clinic.application.ClinicService;
import com.dentalclinic.clinical.application.ClinicalService;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.medicalintake.domain.*;
import com.dentalclinic.medicalintake.domain.IntakeAnswers.ConfirmedBy;
import com.dentalclinic.medicalintake.infrastructure.*;
import com.dentalclinic.patient.application.PatientService;
import com.dentalclinic.patient.domain.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import static com.dentalclinic.medicalintake.application.IntakeDefinition.*;

@Service
@Transactional
public class MedicalIntakeService {
    private final PatientService patients;
    private final ClinicalService clinical;
    private final ClinicService clinic;
    private final IntakeDraftRepository drafts;
    private final IntakePrivacyRepository privacy;
    private final IntakeDeclarationRepository declarations;
    private final Clock clock;
    private final ObjectMapper json;

    public MedicalIntakeService(PatientService patients, ClinicalService clinical, ClinicService clinic,
            IntakeDraftRepository drafts, IntakePrivacyRepository privacy, IntakeDeclarationRepository declarations,
            Clock clock, ObjectMapper json) {
        this.patients = patients;
        this.clinical = clinical;
        this.clinic = clinic;
        this.drafts = drafts;
        this.privacy = privacy;
        this.declarations = declarations;
        this.clock = clock;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public IntakeViews.Status status(AuthenticatedUser actor) { return IntakeViews.status(own(actor)); }

    // Serialize reads with writes too: personal + answers + version are one consistent snapshot.
    public IntakeViews.Draft draft(AuthenticatedUser actor) { return view(patients.lockForIntake(actor)); }

    public IntakeViews.Draft savePersonal(AuthenticatedUser actor, IntakeCommands.Personal command) {
        Patient p = patients.lockForIntake(actor);
        expected(p, command.expectedVersion());
        IntakeValidation.personal(command.personal(), today(), false);
        p.saveIntakePersonal(command.personal().normalized(), clock.instant());
        return view(p);
    }

    public IntakeViews.Draft acknowledgePrivacy(AuthenticatedUser actor, IntakeCommands.Privacy command) {
        Patient p = patients.lockForIntake(actor);
        if (!NOTICE_VERSION.equals(command.noticeVersion()) || !Boolean.TRUE.equals(command.acknowledged())
                || !Boolean.TRUE.equals(command.processingAccepted()))
            throw new IntakeException("INTAKE_PRIVACY_REQUIRED", "Lee y confirma el aviso vigente del prototipo antes de guardar antecedentes.");
        // Exact replay is harmless; stale tabs cannot change answers through this operation.
        if (currentPrivacy(p).isPresent()) return view(p);
        expected(p, command.expectedVersion());
        privacy.saveAndFlush(new IntakePrivacyEvidence(p.getId(), actor.userId(), NOTICE_VERSION,
                NOTICE_TEXT, hash(NOTICE_TEXT), evidenceTime()));
        p.advanceIntake(false, clock.instant());
        return view(p);
    }

    public IntakeViews.Draft saveMedical(AuthenticatedUser actor, IntakeCommands.MedicalSave command) {
        Patient p = patients.lockForIntake(actor);
        expected(p, command.expectedVersion());
        requirePrivacy(p);
        if (command.medical() == null) throw invalid();
        IntakeValidation.medical(command.medical(), false);
        IntakeDraft draft = editable(p);
        draft.saveMedical(command.medical());
        drafts.save(draft);
        p.advanceIntake(false, clock.instant());
        return view(p);
    }

    public IntakeViews.Draft saveDental(AuthenticatedUser actor, IntakeCommands.DentalSave command) {
        Patient p = patients.lockForIntake(actor);
        expected(p, command.expectedVersion());
        requirePrivacy(p);
        if (command.dental() == null) throw invalid();
        IntakeValidation.dental(command.dental(), false);
        IntakeDraft draft = editable(p);
        draft.saveDental(command.dental());
        drafts.save(draft);
        p.advanceIntake(false, clock.instant());
        return view(p);
    }

    public IntakeViews.ConfirmationResult confirm(AuthenticatedUser actor, IntakeCommands.Confirmation command, boolean revision) {
        Patient p = patients.lockForIntake(actor);
        if (command.operationId() == null) throw invalid();
        String requestHash = confirmationHash(command, revision);
        var existing = declarations.findByPatientIdAndOperationId(p.getId(), command.operationId());
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(requestHash))
                throw new IntakeException("INTAKE_IDEMPOTENCY_CONFLICT", "La operación ya se utilizó con otra confirmación.");
            return result(p, existing.get(), command.expectedVersion() + 1);
        }
        expected(p, command.expectedVersion());
        if (revision != (p.getMedicalOnboardingProgress() == MedicalOnboardingProgress.COMPLETED))
            throw new IntakeException("INTAKE_STATE_CONFLICT", "Consulta el estado actual y utiliza la operación correspondiente.");
        if (!QUESTIONNAIRE_VERSION.equals(command.questionnaireVersion())
                || !DECLARATION_VERSION.equals(command.declarationVersion()))
            throw new IntakeException("INTAKE_DEFINITION_OUTDATED", "Recarga el cuestionario y revisa la información vigente.");
        if (!Boolean.TRUE.equals(command.declarationConfirmed()) || command.confirmedBy() == null) throw invalid();
        IntakePrivacyEvidence evidence = requirePrivacy(p);
        IntakeValidation.personal(p.intakePersonal(), today(), true);
        boolean minor = IntakeValidation.isMinor(p.intakePersonal(), today());
        if (minor != (command.confirmedBy() == ConfirmedBy.RESPONSIBLE_ADULT))
            throw new IntakeException("INTAKE_VALIDATION_FAILED", "Revisa quién confirma la declaración del paciente.");
        IntakeDraft draft = editable(p);
        IntakeValidation.medical(draft.getMedical(), true);
        IntakeValidation.dental(draft.getDental(), true);
        int nextRevision = declarations.findFirstByPatientIdOrderByRevisionDesc(p.getId())
                .map(d -> d.getRevision() + 1).orElse(1);
        Long recordId = clinical.ensureRecordForIntake(p.getId());
        IntakeDeclaration declaration = declarations.saveAndFlush(new IntakeDeclaration(p.getId(), recordId,
                actor.userId(), nextRevision, command.operationId(), requestHash, QUESTIONNAIRE_VERSION,
                p.intakePersonal(), draft.getMedical(), draft.getDental(), evidence.getId(),
                DECLARATION_VERSION, DECLARATION_TEXT, command.confirmedBy(), evidenceTime()));
        p.advanceIntake(true, clock.instant());
        return result(p, declaration, p.getIntakeVersion());
    }

    @Transactional(readOnly = true)
    public Optional<IntakeViews.Declaration> latest(AuthenticatedUser actor) {
        return declarations.findFirstByPatientIdOrderByRevisionDesc(own(actor).getId()).map(this::declarationView);
    }

    @Transactional(readOnly = true)
    public Page<IntakeViews.Declaration> ownDeclarations(AuthenticatedUser actor, int page, int size) {
        return history(own(actor).getId(), page, size);
    }

    @Transactional(readOnly = true)
    public Page<IntakeViews.Declaration> professionalDeclarations(AuthenticatedUser actor, Long patientId, int page, int size) {
        if (actor.role() != Role.OWNER && actor.role() != Role.DENTIST)
            throw new ForbiddenOperationException("Solo personal odontológico autorizado.");
        patients.get(patientId);
        return history(patientId, page, size);
    }

    private Page<IntakeViews.Declaration> history(Long id, int page, int size) {
        if (page < 0 || size < 1 || size > 50) throw invalid();
        return declarations.findByPatientIdOrderByRevisionDesc(id, PageRequest.of(page, size)).map(this::declarationView);
    }
    private Patient own(AuthenticatedUser actor) {
        if (actor.role() != Role.PATIENT) throw new ForbiddenOperationException("Solo pacientes.");
        return patients.findByUserId(actor.userId()).orElseThrow(NotFoundException::new);
    }
    private IntakeDraft editable(Patient p) { return drafts.findById(p.getId()).orElseGet(() -> new IntakeDraft(p.getId())); }
    private Optional<IntakePrivacyEvidence> currentPrivacy(Patient p) {
        return privacy.findByPatientIdAndNoticeVersion(p.getId(), NOTICE_VERSION);
    }
    private IntakePrivacyEvidence requirePrivacy(Patient p) {
        return currentPrivacy(p).orElseThrow(() -> new IntakeException("INTAKE_PRIVACY_REQUIRED",
                "Lee y confirma el aviso vigente del prototipo antes de guardar antecedentes."));
    }
    private IntakeViews.Draft view(Patient p) {
        IntakeDraft draft = editable(p);
        return new IntakeViews.Draft(IntakeViews.status(p), p.intakePersonal(), draft.getMedical(), draft.getDental(),
                IntakeViews.privacy(currentPrivacy(p).orElse(null)), QUESTIONNAIRE_VERSION);
    }
    private IntakeViews.Declaration declarationView(IntakeDeclaration d) {
        return IntakeViews.declaration(d, privacy.findById(d.getPrivacyEvidenceId()).orElseThrow());
    }
    private IntakeViews.ConfirmationResult result(Patient p, IntakeDeclaration d, long version) {
        return new IntakeViews.ConfirmationResult(version, new PatientService.MedicalOnboardingSummary(
                p.getMedicalOnboardingRequirement(), MedicalOnboardingProgress.COMPLETED, false), declarationView(d));
    }
    private void expected(Patient p, Long version) {
        if (version == null || version < 0) throw invalid();
        if (p.getIntakeVersion() != version) throw new IntakeException("INTAKE_VERSION_CONFLICT",
                "El cuestionario cambió en otra pestaña. Recupera el borrador antes de continuar.");
    }
    private LocalDate today() { return clock.instant().atZone(ZoneId.of(clinic.get().getTimezone())).toLocalDate(); }
    private java.time.Instant evidenceTime() { return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    private IntakeException invalid() { return new IntakeException("INTAKE_VALIDATION_FAILED", "Revisa los datos del cuestionario."); }
    private String confirmationHash(IntakeCommands.Confirmation command, boolean revision) {
        try { return hash(json.writeValueAsString(command) + ":" + revision); }
        catch (JsonProcessingException e) { throw new IllegalStateException("No se pudo procesar la confirmación"); }
    }
}

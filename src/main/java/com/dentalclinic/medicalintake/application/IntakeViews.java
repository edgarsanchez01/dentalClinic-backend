package com.dentalclinic.medicalintake.application;

import java.time.Instant;
import com.dentalclinic.medicalintake.domain.*;
import com.dentalclinic.medicalintake.domain.IntakeAnswers.*;
import com.dentalclinic.patient.domain.*;
import com.dentalclinic.patient.application.PatientService.MedicalOnboardingSummary;

/** Explicit DTO mapping: persistence entities never cross the HTTP boundary. */
public final class IntakeViews {
    private IntakeViews() { }
    public record Status(MedicalOnboardingRequirement requirement, MedicalOnboardingProgress progress,
                         boolean pending, long version) { }
    public record Draft(Status status, IntakePersonalData personal, Medical medical, Dental dental,
                        Privacy privacy, String questionnaireVersion) {
        @Override public String toString() { return "Draft[redacted]"; }
    }
    public record Privacy(Long id, String noticeVersion, String noticeText, String noticeHash,
                          boolean acknowledged, boolean processingAccepted, Long actorUserId, Instant acceptedAt) { }
    public record Declaration(Long id, int revision, Long clinicalRecordId, Long actorUserId,
            String questionnaireVersion, IntakePersonalData personal, Medical medical, Dental dental,
            Privacy privacy, String declarationVersion, String declarationText, boolean declarationConfirmed,
            ConfirmedBy confirmedBy, Instant confirmedAt) {
        @Override public String toString() { return "Declaration[redacted]"; }
    }
    public record ConfirmationResult(long version, MedicalOnboardingSummary medicalOnboarding, Declaration declaration) {
        @Override public String toString() { return "ConfirmationResult[redacted]"; }
    }
    public static Status status(Patient p) {
        return new Status(p.getMedicalOnboardingRequirement(), p.getMedicalOnboardingProgress(),
                p.isMedicalOnboardingPending(), p.getIntakeVersion());
    }
    public static Privacy privacy(IntakePrivacyEvidence p) {
        return p == null ? null : new Privacy(p.getId(), p.getNoticeVersion(), p.getNoticeText(), p.getNoticeHash(),
                p.isAcknowledged(), p.isProcessingAccepted(), p.getActorUserId(), p.getAcceptedAt());
    }
    public static Declaration declaration(IntakeDeclaration d, IntakePrivacyEvidence p) {
        return new Declaration(d.getId(), d.getRevision(), d.getClinicalRecordId(), d.getActorUserId(),
                d.getQuestionnaireVersion(), d.getPersonal(), d.getMedical(), d.getDental(), privacy(p),
                d.getDeclarationVersion(), d.getDeclarationText(), true, d.getConfirmedBy(), d.getConfirmedAt());
    }
}

package com.dentalclinic.medicalintake;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import com.dentalclinic.appointment.integration.AppointmentIntegrationTestSupport;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.medicalintake.application.*;
import com.dentalclinic.medicalintake.domain.*;
import com.dentalclinic.medicalintake.domain.IntakeAnswers.*;
import com.dentalclinic.patient.application.PatientService;
import com.dentalclinic.patient.domain.*;
import static com.dentalclinic.medicalintake.application.IntakeDefinition.*;

/** Full HTTP/security/application/JPA over ephemeral PG16 and real V001–V019 DDL. */
class MedicalIntakeIntegrationTest extends AppointmentIntegrationTestSupport {
    private static final String BASE = "/api/patients/me/medical-intake";
    private static final String SECRET = "synthetic-medical-marker-9d2";
    @Autowired MedicalIntakeService intake;
    @Autowired PatientService patients;
    @Autowired com.dentalclinic.clinical.application.ClinicalService clinical;

    private AuthenticatedUser actor() { return new AuthenticatedUser(patientUser.getId(), Role.PATIENT); }
    private long version() { return patientRepository.findById(patient.getId()).orElseThrow().getIntakeVersion(); }
    private IntakePersonalData adult() {
        return new IntakePersonalData("Paciente", "Uno", LocalDate.of(2000, 1, 1), "5550101", true,
                "Contacto", "Amistad", "5550102", null, null, null);
    }
    private IntakePersonalData minor(boolean guardian) {
        return new IntakePersonalData("Menor", "Uno", LocalDate.of(2016, 1, 1), null, false,
                "Contacto distinto", "Familia", "5550103", guardian ? "Adulto responsable" : null,
                guardian ? IntakePersonalData.ResponsibleRelationship.MOTHER : null, guardian ? "5550104" : null);
    }
    private Medical medical(Answer answer) {
        Question q = new Question(answer, answer == Answer.YES ? SECRET : null);
        return new Medical(q, q, q, q, q, q);
    }
    private Dental dental() {
        Question q = new Question(Answer.UNKNOWN, null);
        return new Dental(q, q, q, q, "Revisión inicial");
    }
    private void privacy() {
        intake.acknowledgePrivacy(actor(), new IntakeCommands.Privacy(version(), NOTICE_VERSION, true, true));
    }
    private void ready(IntakePersonalData personal) {
        intake.savePersonal(actor(), new IntakeCommands.Personal(version(), personal));
        privacy();
        intake.saveMedical(actor(), new IntakeCommands.MedicalSave(version(), medical(Answer.YES)));
        intake.saveDental(actor(), new IntakeCommands.DentalSave(version(), dental()));
    }
    private IntakeCommands.Confirmation confirmation(ConfirmedBy who) {
        return new IntakeCommands.Confirmation(version(), UUID.randomUUID(), QUESTIONNAIRE_VERSION,
                DECLARATION_VERSION, true, who);
    }
    private ResultActions send(String method, String path, Object body, String token) throws Exception {
        return mockMvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), path)
                .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body)));
    }

    @Test void publicRegistrationCreatesExplicitRequiredStateAndMinimalMe() throws Exception {
        String email = "new-" + UUID.randomUUID() + "@dc.test";
        var registered = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(
                objectMapper.writeValueAsBytes(Map.of("email", email, "password", PASSWORD,
                        "firstName", "Nuevo", "lastName", "Paciente"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("user.role").value("PATIENT")).andReturn();
        var user = userRepository.findByEmail(email).orElseThrow();
        var p = patientRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(p.getMedicalOnboardingRequirement()).isEqualTo(MedicalOnboardingRequirement.REQUIRED);
        assertThat(p.getMedicalOnboardingProgress()).isEqualTo(MedicalOnboardingProgress.NOT_STARTED);
        assertThat(p.isMedicalOnboardingPending()).isTrue();
        String registrationToken = objectMapper.readTree(registered.getResponse().getContentAsString()).get("accessToken").asText();
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(registrationToken)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("patientId").value(p.getId()))
                .andExpect(jsonPath("medicalOnboarding.requirement").value("REQUIRED"))
                .andExpect(jsonPath("medicalOnboarding.progress").value("NOT_STARTED"))
                .andExpect(jsonPath("medicalOnboarding.pending").value(true))
                .andExpect(jsonPath("medical").doesNotExist()).andExpect(jsonPath("declarations").doesNotExist());
    }

    @Test void existingPatientsExplicitlyExemptRegardlessOfEmptyData() throws Exception {
        assertThat(patient.getBirthDate()).isNull();
        mockMvc.perform(get(BASE + "/status").header("Authorization", bearer(loginToken(patientUser))))
                .andExpect(status().isOk()).andExpect(jsonPath("requirement").value("EXEMPT_EXISTING"))
                .andExpect(jsonPath("pending").value(false)).andExpect(jsonPath("progress").value("NOT_STARTED"));
        assertThatCode(() -> patients.assertMedicalBookingAllowed(actor())).doesNotThrowAnyException();
    }

    @Test void rolesWithoutPatientProfileHaveNoMeSummaryOrBookingBlock() throws Exception {
        for (var user : List.of(owner, dentistUser, receptionist)) {
            mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(loginToken(user))))
                    .andExpect(status().isOk()).andExpect(jsonPath("medicalOnboarding").doesNotExist());
            assertThatCode(() -> patients.assertMedicalBookingAllowed(new AuthenticatedUser(user.getId(), user.getRole())))
                    .doesNotThrowAnyException();
        }
    }

    @Test void definitionIsPrototypeAndOwnerInstallationStatusRemainsIndependent() throws Exception {
        String token = loginToken(patientUser);
        mockMvc.perform(get(BASE + "/definition").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("legalReviewPending").value(true))
                .andExpect(jsonPath("dentalReviewPending").value(true))
                .andExpect(jsonPath("noticeText").value(NOTICE_TEXT))
                .andExpect(jsonPath("noticeHash").value(hash(NOTICE_TEXT)))
                .andExpect(header().string("Cache-Control", "no-store"));
        String before = mockMvc.perform(get("/api/onboarding/status")).andReturn().getResponse().getContentAsString();
        ready(adult());
        intake.confirm(actor(), confirmation(ConfirmedBy.PATIENT), false);
        assertThat(mockMvc.perform(get("/api/onboarding/status")).andReturn().getResponse().getContentAsString()).isEqualTo(before);
    }

    @Test void privacyIsRequiredBeforeAnyMedicalOrDentalWriteAndEvidenceIsIdempotent() throws Exception {
        String token = loginToken(patientUser);
        send("PUT", BASE + "/draft/medical", new IntakeCommands.MedicalSave(version(), medical(Answer.YES)), token)
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("INTAKE_PRIVACY_REQUIRED"));
        send("PUT", BASE + "/draft/dental", new IntakeCommands.DentalSave(version(), dental()), token)
                .andExpect(status().isConflict());
        assertThat(countRows("patient_intake_draft")).isZero();
        assertThat(version()).isZero();
        send("POST", BASE + "/privacy-acknowledgements", new IntakeCommands.Privacy(0L, NOTICE_VERSION, true, false), token)
                .andExpect(status().isConflict());
        send("POST", BASE + "/privacy-acknowledgements", new IntakeCommands.Privacy(0L, "obsolete", true, true), token)
                .andExpect(status().isConflict());
        var command = new IntakeCommands.Privacy(0L, NOTICE_VERSION, true, true);
        send("POST", BASE + "/privacy-acknowledgements", command, token).andExpect(status().isOk());
        send("POST", BASE + "/privacy-acknowledgements", command, token).andExpect(status().isOk());
        assertThat(countRows("patient_intake_privacy")).isEqualTo(1);
        assertThat(version()).isEqualTo(1);
    }

    @ParameterizedTest @EnumSource(Answer.class)
    void allFourMedicalAnswerStatesRoundTripAndComplete(Answer answer) throws Exception {
        String token = loginToken(patientUser);
        ready(adult());
        send("PUT", BASE + "/draft/medical", new IntakeCommands.MedicalSave(version(), medical(answer)), token)
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        mockMvc.perform(get(BASE + "/draft").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("medical.allergies.answer").value(answer.name()));
        send("POST", BASE + "/complete", confirmation(ConfirmedBy.PATIENT), token)
                .andExpect(status().isOk()).andExpect(jsonPath("declaration.medical.allergies.answer").value(answer.name()));
    }

    @Test void draftIsDurableAcrossSessionsAndContainsNoProfessionalNotes() throws Exception {
        ready(adult());
        clinical.saveRecord(patient.getId(), new com.dentalclinic.clinical.application.ClinicalService.RecordData(
                "Profesional", "Profesional", "Profesional", "Profesional"));
        String response = mockMvc.perform(get(BASE + "/draft").header("Authorization", bearer(loginToken(patientUser))))
                .andExpect(status().isOk()).andExpect(jsonPath("medical.allergies.details").value(SECRET))
                .andExpect(jsonPath("personal.emergencyContactName").value("Contacto"))
                .andExpect(jsonPath("status.progress").value("IN_PROGRESS"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("Profesional", "medicalBackground", "clinicalHistory", "administrativeNotes");
    }

    @Test void partialDraftAllowedButIncompleteConfirmationDoesNotWriteRecordOrDeclaration() {
        privacy();
        intake.saveMedical(actor(), new IntakeCommands.MedicalSave(version(), new Medical(null, null, null, null, null, null)));
        long before = version();
        assertThatThrownBy(() -> intake.confirm(actor(), confirmation(ConfirmedBy.PATIENT), false)).isInstanceOf(IntakeException.class);
        assertThat(version()).isEqualTo(before);
        assertThat(countRows("clinical_record")).isZero();
        assertThat(countRows("patient_intake_declaration")).isZero();
    }

    @Test void yesDetailsOptionalAndOtherAnswersCannotCarryDetails() throws Exception {
        ready(adult());
        String token = loginToken(patientUser);
        var q = new Question(Answer.YES, null);
        send("PUT", BASE + "/draft/medical", new IntakeCommands.MedicalSave(version(), new Medical(q,q,q,q,q,q)), token)
                .andExpect(status().isOk());
        var invalid = new Medical(new Question(Answer.NO, SECRET), q,q,q,q,q);
        send("PUT", BASE + "/draft/medical", new IntakeCommands.MedicalSave(version(), invalid), token)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INTAKE_VALIDATION_FAILED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(SECRET))));
        var tooLong = new Medical(new Question(Answer.YES, "X".repeat(2001)), q,q,q,q,q);
        send("PUT", BASE + "/draft/medical", new IntakeCommands.MedicalSave(version(), tooLong), token)
                .andExpect(status().isBadRequest());
    }

    @Test void minorRequiresDistinctGuardianAndResponsibleConfirmation() throws Exception {
        ready(minor(false));
        var initial = confirmation(ConfirmedBy.RESPONSIBLE_ADULT);
        send("POST", BASE + "/complete", initial, loginToken(patientUser)).andExpect(status().isBadRequest());
        intake.savePersonal(actor(), new IntakeCommands.Personal(version(), minor(true)));
        send("POST", BASE + "/complete", confirmation(ConfirmedBy.PATIENT), loginToken(patientUser))
                .andExpect(status().isBadRequest());
        send("POST", BASE + "/complete", confirmation(ConfirmedBy.RESPONSIBLE_ADULT), loginToken(patientUser))
                .andExpect(status().isOk()).andExpect(jsonPath("declaration.personal.responsibleAdultName").value("Adulto responsable"))
                .andExpect(jsonPath("declaration.personal.emergencyContactName").value("Contacto distinto"))
                .andExpect(jsonPath("declaration.confirmedBy").value("RESPONSIBLE_ADULT"));
        assertThat(patientRepository.findById(patient.getId()).orElseThrow().getFirstName()).isEqualTo("Menor");
        assertThat(patientRepository.count()).isEqualTo(1);
    }

    @Test void adultsMustProvidePhoneWhenAvailableButCanExplicitlyDeclareUnavailable() {
        var noPhone = new IntakePersonalData("Paciente", "Uno", LocalDate.of(2000,1,1), null, true,
                "Contacto", "Amistad", "555", null,null,null);
        ready(noPhone);
        assertThatThrownBy(() -> intake.confirm(actor(), confirmation(ConfirmedBy.PATIENT), false)).isInstanceOf(IntakeException.class);
        var unavailable = new IntakePersonalData("Paciente", "Uno", LocalDate.of(2000,1,1), null, false,
                "Contacto", "Amistad", "555", null,null,null);
        intake.savePersonal(actor(), new IntakeCommands.Personal(version(), unavailable));
        assertThat(intake.confirm(actor(), confirmation(ConfirmedBy.PATIENT), false).medicalOnboarding().pending()).isFalse();
    }

    @Test void birthDateRequiredOnCompletionAndInvalidOrFutureDatesRejectedWithoutEcho() throws Exception {
        String token = loginToken(patientUser);
        String personal = objectMapper.writeValueAsString(adult());
        for (String date : List.of("2026-02-30", "not-a-date", "2026-10-07", "0000-01-01")) {
            String body = "{\"expectedVersion\":" + version() + ",\"personal\":" + personal.replace("2000-01-01", date) + "}";
            var response = mockMvc.perform(put(BASE + "/draft/personal").header("Authorization", bearer(token))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain(date);
        }
        ready(new IntakePersonalData("Paciente", "Uno", null, "555", true,"Contacto","Amistad","555",null,null,null));
        send("POST", BASE + "/complete", confirmation(ConfirmedBy.PATIENT), token).andExpect(status().isBadRequest());
        assertThat(countRows("patient_intake_declaration")).isZero();
    }

    @Test void completionReplayIsStableEvenAfterLaterDraftEditAndDoesNotDuplicateEvidence() throws Exception {
        patients.requireMedicalOnboarding(patient.getId());
        ready(adult());
        String token = loginToken(patientUser);
        var command = confirmation(ConfirmedBy.PATIENT);
        String first = send("POST", BASE + "/complete", command, token).andExpect(status().isOk())
                .andExpect(jsonPath("medicalOnboarding.pending").value(false)).andReturn().getResponse().getContentAsString();
        intake.saveMedical(actor(), new IntakeCommands.MedicalSave(version(), medical(Answer.NO)));
        String replay = send("POST", BASE + "/complete", command, token).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        assertThat(countRows("patient_intake_declaration")).isEqualTo(1);
        assertThat(countRows("patient_intake_privacy")).isEqualTo(1);
        var conflicting = new IntakeCommands.Confirmation(command.expectedVersion(), command.operationId(),
                QUESTIONNAIRE_VERSION, DECLARATION_VERSION, false, ConfirmedBy.PATIENT);
        send("POST", BASE + "/complete", conflicting, token).andExpect(status().isConflict())
                .andExpect(jsonPath("code").value("INTAKE_IDEMPOTENCY_CONFLICT"));
    }

    @Test void laterRevisionsAreAppendOnlyKeepBookingUnlockedAndPreserveProfessionalRecord() throws Exception {
        patients.requireMedicalOnboarding(patient.getId());
        var professional = clinical.saveRecord(patient.getId(), new com.dentalclinic.clinical.application.ClinicalService.RecordData(
                "Antecedente profesional", "Alergia profesional", "Historia profesional", "Observación profesional"));
        ready(adult());
        var first = intake.confirm(actor(), confirmation(ConfirmedBy.PATIENT), false).declaration();
        intake.saveMedical(actor(), new IntakeCommands.MedicalSave(version(), medical(Answer.NO)));
        assertThat(intake.status(actor()).pending()).isFalse();
        assertThat(intake.status(actor()).progress()).isEqualTo(MedicalOnboardingProgress.COMPLETED);
        assertThatCode(() -> patients.assertMedicalBookingAllowed(actor())).doesNotThrowAnyException();
        var next = intake.confirm(actor(), confirmation(ConfirmedBy.PATIENT), true).declaration();
        assertThat(next.revision()).isEqualTo(2);
        assertThat(next.clinicalRecordId()).isEqualTo(first.clinicalRecordId());
        assertThat(intake.ownDeclarations(actor(), 0, 20).getContent().get(1)).isEqualTo(first);
        assertThat(clinical.getRecord(patient.getId())).isEqualTo(professional);
        assertThat(countRows("clinical_record")).isEqualTo(1);
        assertThat(countRows("clinical_note")).isZero();
        assertThat(countRows("treatment")).isZero();
        assertThat(countRows("tooth_record")).isZero();
        assertThat(countRows("notification")).isZero();
        assertThat(countRows("communication")).isZero();
        mockMvc.perform(get(BASE + "/declarations/latest").header("Authorization", bearer(loginToken(patientUser))))
                .andExpect(status().isOk()).andExpect(jsonPath("revision").value(2));
    }

    @Test void staleTabAndAdministrativeEditBothCauseVersionConflict() throws Exception {
        String token = loginToken(patientUser);
        send("PUT", BASE + "/draft/personal", new IntakeCommands.Personal(0L, adult()), token).andExpect(status().isOk());
        send("PUT", BASE + "/draft/personal", new IntakeCommands.Personal(0L, adult()), token)
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("INTAKE_VERSION_CONFLICT"));
        long previous = version();
        patients.update(patient.getId(), new PatientService.PatientData("Administrativo", "Uno", null, null,null,null), null);
        send("PUT", BASE + "/draft/personal", new IntakeCommands.Personal(previous, adult()), token)
                .andExpect(status().isConflict());
        assertThat(patientRepository.findById(patient.getId()).orElseThrow().getFirstName()).isEqualTo("Administrativo");
    }

    @Test void twoConcurrentSavesOnlyOneWins() throws Exception {
        var command = new IntakeCommands.Personal(version(), adult());
        var results = race(() -> { intake.savePersonal(actor(), command); return "saved"; });
        assertThat(results).containsExactlyInAnyOrder("saved", "INTAKE_VERSION_CONFLICT");
        assertThat(version()).isEqualTo(1);
    }

    @Test void concurrentConfirmationRetriesCreateOneDeclaration() throws Exception {
        ready(adult());
        var command = confirmation(ConfirmedBy.PATIENT);
        var results = race(() -> intake.confirm(actor(), command, false).declaration().id().toString());
        assertThat(new HashSet<>(results)).hasSize(1);
        assertThat(countRows("patient_intake_declaration")).isEqualTo(1);
        assertThat(countRows("patient_intake_privacy")).isEqualTo(1);
    }

    @Test void failureAfterRecordCreationRollsBackRecordDeclarationAndCompletion() throws Exception {
        patients.requireMedicalOnboarding(patient.getId());
        ready(adult());
        long before = version();
        // Fault injection solely inside the guarded ephemeral database, not a mock transaction.
        jdbcTemplate.execute("CREATE FUNCTION intake_test_reject() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic rejection' USING ERRCODE='23514'; END $$");
        jdbcTemplate.execute("CREATE TRIGGER intake_test_reject BEFORE INSERT ON patient_intake_declaration FOR EACH ROW EXECUTE FUNCTION intake_test_reject()");
        try {
            send("POST",BASE + "/complete",confirmation(ConfirmedBy.PATIENT),loginToken(patientUser))
                    .andExpect(status().isConflict())
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(SECRET))));
        } finally {
            jdbcTemplate.execute("DROP TRIGGER intake_test_reject ON patient_intake_declaration");
            jdbcTemplate.execute("DROP FUNCTION intake_test_reject()");
        }
        assertThat(version()).isEqualTo(before);
        assertThat(intake.status(actor()).pending()).isTrue();
        assertThat(countRows("clinical_record")).isZero();
        assertThat(countRows("patient_intake_declaration")).isZero();
        assertThat(countRows("patient_intake_privacy")).isEqualTo(1);
    }

    @ParameterizedTest @EnumSource(value=Role.class,names={"OWNER","DENTIST","RECEPTIONIST"})
    void staffCanCreateAndRescheduleWhilePatientIsRequired(Role role) throws Exception {
        patients.requireMedicalOnboarding(patient.getId());
        var user = role == Role.OWNER ? owner : role == Role.DENTIST ? dentistUser : receptionist;
        String token = loginToken(user);
        String body = createBody(patient.getId(),dentist.getId(),at(MARTES,10,0),at(MARTES,11,0));
        var created = mockMvc.perform(post("/api/appointments").header("Authorization",bearer(token))
                .header("Idempotency-Key","staff-pending-" + role).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        send("POST","/api/appointments/" + id + "/reschedule",Map.of("startAt",at(MIERCOLES,10,0),
                "endAt",at(MIERCOLES,11,0),"expectedOccurrenceVersion",1),token)
                .andExpect(status().isOk()).andExpect(jsonPath("occurrenceVersion").value(2));
    }

    private List<String> race(Callable<String> action) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<String> attempt = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                try { return action.call(); } catch (IntakeException e) { return e.code(); }
            };
            var a = executor.submit(attempt);
            var b = executor.submit(attempt);
            return List.of(a.get(20,TimeUnit.SECONDS), b.get(20,TimeUnit.SECONDS));
        }
    }

    @Test void roleAndCrossPatientAccessAreDeniedAtHttpAndServiceBoundaries() throws Exception {
        ready(adult());
        intake.confirm(actor(), confirmation(ConfirmedBy.PATIENT), false);
        var otherUser = newUser(Role.PATIENT);
        var other = patientRepository.saveAndFlush(Patient.createForUser(otherUser.getId(), "Otro", "Paciente",null,null,null,null));
        String otherToken = loginToken(otherUser);
        mockMvc.perform(get(BASE + "/draft").header("Authorization", bearer(otherToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("personal.firstName").value("Otro"))
                .andExpect(jsonPath("medical").doesNotExist());
        mockMvc.perform(get(BASE + "/declarations/latest").header("Authorization", bearer(otherToken))).andExpect(status().isNoContent());
        String path = "/api/patients/" + patient.getId() + "/clinical/medical-intake/declarations";
        for (var user : List.of(patientUser, otherUser, receptionist)) {
            mockMvc.perform(get(path).header("Authorization", bearer(loginToken(user)))).andExpect(status().isForbidden());
            assertThatThrownBy(() -> intake.professionalDeclarations(new AuthenticatedUser(user.getId(), user.getRole()), patient.getId(), 0,20))
                    .isInstanceOf(ForbiddenOperationException.class);
        }
        for (var user : List.of(owner, dentistUser)) {
            mockMvc.perform(get(path).header("Authorization", bearer(loginToken(user))))
                    .andExpect(status().isOk()).andExpect(jsonPath("content[0].medical.allergies.details").value(SECRET))
                    .andExpect(header().string("Cache-Control", "no-store"));
            mockMvc.perform(post(path).header("Authorization", bearer(loginToken(user)))).andExpect(status().isForbidden());
        }
        for (var user : List.of(owner,dentistUser,receptionist)) {
            mockMvc.perform(get(BASE + "/draft").header("Authorization", bearer(loginToken(user)))).andExpect(status().isForbidden());
            assertThatThrownBy(() -> intake.draft(new AuthenticatedUser(user.getId(), user.getRole())))
                    .isInstanceOf(ForbiddenOperationException.class);
        }
        mockMvc.perform(get(BASE + "/draft")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/patients/" + other.getId() + "/medical-intake/draft")
                .header("Authorization", bearer(loginToken(patientUser)))).andExpect(status().isNotFound());
        String admin = mockMvc.perform(get("/api/patients/" + patient.getId()).header("Authorization", bearer(loginToken(receptionist))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(admin).doesNotContain(SECRET, "responsibleAdult", "medicalOnboarding", "medicalBackground");
    }

    @Test void requiredCreateAndRescheduleRejectBeforeAnySideEffectsButCancellationAndStaffWork() throws Exception {
        String patientToken = loginToken(patientUser);
        String ownerToken = loginToken(owner);
        String body = createBody(patient.getId(), dentist.getId(), at(MARTES,10,0), at(MARTES,11,0));
        // Staff can create an appointment for a patient whose self-service onboarding is pending.
        patients.requireMedicalOnboarding(patient.getId());
        var created = mockMvc.perform(post("/api/appointments").header("Authorization", bearer(ownerToken))
                .header("Idempotency-Key", "staff-9d2").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        long appointmentId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        var before = effects();
        mockMvc.perform(post("/api/appointments").header("Authorization", bearer(patientToken))
                .header("Idempotency-Key", "patient-9d2").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("MEDICAL_ONBOARDING_REQUIRED"));
        send("POST", "/api/appointments/" + appointmentId + "/reschedule", Map.of("startAt", at(MIERCOLES,10,0),
                "endAt", at(MIERCOLES,11,0), "expectedOccurrenceVersion", 1), patientToken)
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("MEDICAL_ONBOARDING_REQUIRED"));
        assertThat(effects()).isEqualTo(before);
        mockMvc.perform(get("/api/appointments/" + appointmentId).header("Authorization", bearer(patientToken)))
                .andExpect(status().isOk()).andExpect(jsonPath("occurrenceVersion").value(1));
        mockMvc.perform(post("/api/appointments/" + appointmentId + "/cancel").header("Authorization", bearer(patientToken)))
                .andExpect(status().isOk());
        assertThat(countRows("patient_intake_declaration")).isZero();
        ready(adult());
        intake.confirm(actor(),confirmation(ConfirmedBy.PATIENT),false);
        mockMvc.perform(post("/api/appointments").header("Authorization",bearer(patientToken))
                .header("Idempotency-Key","patient-9d2").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        var after = effects();
        mockMvc.perform(post("/api/appointments").header("Authorization",bearer(patientToken))
                .header("Idempotency-Key","patient-9d2").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        assertThat(effects()).isEqualTo(after);
    }

    private Map<String, List<String>> effects() {
        var values = new LinkedHashMap<String,List<String>>();
        for (String table : List.of("appointment","appointment_event","http_idempotency","communication","communication_intention","notification"))
            values.put(table, jdbcTemplate.queryForList("SELECT to_jsonb(t)::text FROM " + table + " t ORDER BY id",String.class));
        return values;
    }
}

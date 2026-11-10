package com.dentalclinic.appointment.integration;

import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import com.dentalclinic.checkout.CheckoutFlowSupport;
import com.dentalclinic.appointment.application.AppointmentService;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ClinicalCompletionApiTest extends CheckoutFlowSupport {
    @Autowired private AppointmentService appointments;
    private org.springframework.test.web.servlet.ResultActions complete(String key, String body,
            com.dentalclinic.identity.infrastructure.security.AuthenticatedUser actor) throws Exception {
        var request = post("/api/appointments/"+scheduled+"/complete").header("Authorization",bearer(actor));
        if(key!=null) request.header("Idempotency-Key",key);
        if(body!=null) request.contentType("application/json").content(body);
        return mvc.perform(request);
    }

    @Test void dentistCompletesWithoutAnyFinancialWritesAndReplayIsDurable() throws Exception {
        var before=jdbc.queryForList("SELECT * FROM charge");
        var first=json(complete("clinical","{\"expectedOccurrenceVersion\":1}",doctor).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")));
        assertThat(jdbc.queryForList("SELECT * FROM charge")).isEqualTo(before);
        assertThat(count("charge_item")).isZero();
        assertThat(count("financial_movement")).isZero();
        assertThat(count("appointment_checkout")).isZero();
        assertThat(count("patient_feedback")).isEqualTo(1);
        assertThat(json(complete("clinical","{\"expectedOccurrenceVersion\":1}",doctor).andExpect(status().isOk()))).isEqualTo(first);
        complete("clinical","{\"expectedOccurrenceVersion\":2}",doctor).andExpect(status().isConflict());
        complete("another","{\"expectedOccurrenceVersion\":1}",doctor).andExpect(status().isConflict());
        assertThat(count("http_idempotency")).isEqualTo(1);
        assertThat(count("patient_feedback")).isEqualTo(1);
        jdbc.update("UPDATE appointment SET dentist_id=? WHERE id=?",otherDentistId,scheduled);
        complete("clinical","{\"expectedOccurrenceVersion\":1}",doctor).andExpect(status().isNotFound());
    }

    @Test void receptionistCannotCompleteOwnerNeedsAssignmentAndLegacyCompatibilityIsOwnerOnly() throws Exception {
        var reception=new com.dentalclinic.identity.infrastructure.security.AuthenticatedUser(receptionId,com.dentalclinic.identity.domain.Role.RECEPTIONIST);
        complete("reception","{\"expectedOccurrenceVersion\":1}",reception).andExpect(status().isForbidden());
        assertThatThrownBy(() -> appointments.complete(scheduled,reception,1,"direct")).isInstanceOf(ForbiddenOperationException.class);
        complete("owner","{\"expectedOccurrenceVersion\":1}",owner).andExpect(status().isForbidden());
        complete(null,null,doctor).andExpect(status().isBadRequest());
        assertThatThrownBy(() -> appointments.complete(scheduled,doctor)).isInstanceOf(ForbiddenOperationException.class);
        jdbc.update("UPDATE appointment SET dentist_id=(SELECT id FROM dentist WHERE user_id=?) WHERE id=?",ownerId,scheduled);
        complete(null,null,owner).andExpect(status().isOk());
        assertThat(count("financial_movement")).isZero();
        assertThat(count("charge")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"{}","null","{\"expectedOccurrenceVersion\":0}",
            "{\"expectedOccurrenceVersion\":\"1\"}","{\"expectedOccurrenceVersion\":1.2}",
            "{\"expectedOccurrenceVersion\":1,\"notes\":\"not saved\"}",
            "{\"expectedOccurrenceVersion\":2,\"expectedOccurrenceVersion\":1}"})
    void strictTransitionHasNoClinicalPayload(String body) throws Exception {
        complete("invalid",body,doctor).andExpect(status().isBadRequest());
        assertNoCheckout();
        assertThat(count("http_idempotency")).isZero();
    }

    @Test void staleVersionDoesNotConsumeKeyAndCanRecoverAfterExplicitRefresh() throws Exception {
        jdbc.update("UPDATE appointment SET occurrence_version=2 WHERE id=?",scheduled);
        complete("version","{\"expectedOccurrenceVersion\":1}",doctor).andExpect(status().isConflict());
        assertThat(count("http_idempotency")).isZero();
        complete("version","{\"expectedOccurrenceVersion\":2}",doctor).andExpect(status().isOk());
    }

    @ParameterizedTest @ValueSource(strings={"same","different-key","different-body"})
    void concurrentClinicalCommandsDoNotDuplicateEffects(String mode) throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<String> a=() -> {start.await();return outcome("a",1);};
            Callable<String> b=() -> {start.await();return outcome(mode.equals("different-key")?"b":"a",mode.equals("different-body")?2:1);};
            var first=executor.submit(a);var second=executor.submit(b);start.countDown();
            var results=List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
            assertThat(results).contains("COMPLETED");
            assertThat(results.stream().filter("COMPLETED"::equals).count()).isEqualTo(mode.equals("same")?2:1);
        }
        assertThat(count("patient_feedback")).isEqualTo(1);
        assertThat(count("financial_movement")).isZero();
        assertThat(count("charge")).isEqualTo(1);
        assertThat(count("http_idempotency")).isEqualTo(1);
    }

    private String outcome(String key,int version) {
        try { return appointments.complete(scheduled,doctor,version,key).status().name(); }
        catch(com.dentalclinic.scheduling.domain.ScheduleConflictException expected) { return "CONFLICT"; }
    }
}

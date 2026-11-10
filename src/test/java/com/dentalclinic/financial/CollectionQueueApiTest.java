package com.dentalclinic.financial;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.dentalclinic.financial.application.CollectionQueueService;
import com.dentalclinic.financial.application.command.*;
import com.dentalclinic.financial.domain.PaymentMethod;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CollectionQueueApiTest extends CollectionFlowSupport {
    @Test void paginationDoesNotDropRowsAcrossVerificationBatchesOrEqualStartTimes() throws Exception {
        jdbc.update("INSERT INTO appointment(patient_id,dentist_id,created_by_user_id,start_at,end_at,duration_minutes,status,completed_at) "
                + "SELECT ?,?,?, '2026-10-14T00:00:00Z'::timestamptz,'2026-10-14T01:00:00Z'::timestamptz,60,'COMPLETED',now() FROM generate_series(1,102)",
                patientId,dentistId,ownerId);
        var last=page("?state=NO_CHARGE&size=100&page=1");
        assertThat(last.path("totalElements").asLong()).isEqualTo(103);
        assertThat(last.path("totalPages").asInt()).isEqualTo(2);
        assertThat(last.path("content").size()).isEqualTo(3);
        long previous=0;
        for(var item:last.path("content")) {
            long id=item.at("/appointment/id").asLong();
            assertThat(id).isGreaterThan(previous);previous=id;
            assertThat(item.path("pendingBalance").isNull()).isTrue();
        }
        assertThat(last.at("/summary/verifiedPendingBalance").decimalValue()).isEqualByComparingTo("0");
        assertThat(last.at("/summary/counts/REVIEW_REQUIRED").asLong()).isEqualTo(1);
    }
    @Autowired private CollectionQueueService queue;
    private com.fasterxml.jackson.databind.JsonNode page(String query) throws Exception {
        return json(mvc.perform(get("/api/appointments/collection-queue"+query).header("Authorization",bearer(reception)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")));
    }
    private Long newCharge(Long appointment) {
        return operations.confirmCharge(new ConfirmChargeCommand(patientId,appointment,
                List.of(new ChargeItemData(null,dentistId,"Administrativo",1,new BigDecimal("500"))),null,
                UUID.randomUUID().toString()),owner).getId();
    }

    @Test void verifiedClassificationPrecedesPaginationAndSummaryCountsAllFilteredAppointments() throws Exception {
        Long pending=appointment(patientId,dentistId,"COMPLETED");newCharge(pending);
        Long partial=appointment(patientId,dentistId,"COMPLETED");Long pc=newCharge(partial);
        operations.registerPayment(new RegisterPaymentCommand(pc,PaymentMethod.CASH,new BigDecimal("200"),null,"partial"),reception);
        Long settled=appointment(patientId,dentistId,"COMPLETED");Long sc=newCharge(settled);
        operations.registerPayment(new RegisterPaymentCommand(sc,PaymentMethod.CARD,new BigDecimal("500"),null,"paid"),reception);
        Long ambiguous=appointment(patientId,dentistId,"COMPLETED");newCharge(ambiguous);newCharge(ambiguous);
        Long cancelled=appointment(patientId,dentistId,"COMPLETED");Long cc=newCharge(cancelled);
        operations.cancelCharge(new CancelChargeCommand(cc,"Error","cancel"),owner);
        var first=page("?size=1");
        assertThat(first.path("totalElements").asLong()).isEqualTo(6);
        assertThat(first.path("totalPages").asInt()).isEqualTo(6);
        var summary=first.path("summary");
        assertThat(summary.path("completedCount").asLong()).isEqualTo(7);
        assertThat(summary.at("/counts/NO_CHARGE").asInt()).isEqualTo(2);
        assertThat(summary.at("/counts/PENDING").asInt()).isEqualTo(1);
        assertThat(summary.at("/counts/PARTIAL").asInt()).isEqualTo(1);
        assertThat(summary.at("/counts/SETTLED").asInt()).isEqualTo(1);
        assertThat(summary.at("/counts/REVIEW_REQUIRED").asInt()).isEqualTo(2);
        assertThat(summary.path("verifiedPendingBalance").decimalValue()).isEqualByComparingTo("800");
        Set<Long> ids=new HashSet<>();
        for(int i=0;i<6;i++) {
            var response=page("?size=1&page="+i);
            assertThat(response.path("summary")).isEqualTo(summary);
            assertThat(response.path("content").size()).isEqualTo(1);
            assertThat(ids.add(response.at("/content/0/appointment/id").asLong())).isTrue();
        }
        assertThat(ids).doesNotContain(settled);
        assertThat(page("?size=1&page=6").path("content").size()).isZero();
        var noCharge=page("?state=NO_CHARGE");
        assertThat(noCharge.path("totalElements").asLong()).isEqualTo(2);
        for(var item:noCharge.path("content")) {
            assertThat(item.path("pendingBalance").isNull()).isTrue();
            assertThat(item.path("totalAmount").isNull()).isTrue();
            assertThat(item.path("activeChargeId").isNull()).isTrue();
            assertThat(item.path("financialContextToken").asText()).startsWith("v1:");
        }
        var review=page("?state=REVIEW_REQUIRED");
        for(var item:review.path("content")) {
            assertThat(item.path("pendingBalance").isNull()).isTrue();
            assertThat(item.path("reviewReason").asText()).isNotBlank();
        }
        assertThat(page("?includeSettled=true").path("totalElements").asLong()).isEqualTo(7);
        assertThat(page("?state=SETTLED").path("totalElements").asLong()).isEqualTo(1);
        assertClinicallyCompleted();
    }

    @Test void filtersAndAdministrativeSecurityNeverExposeClinicalData() throws Exception {
        var filtered=page("?dentistId="+otherDentistId);
        assertThat(filtered.path("totalElements").asLong()).isZero();
        assertThat(filtered.at("/summary/completedCount").asLong()).isZero();
        assertThat(page("?from=2027-01-01T00:00:00Z").path("totalElements").asLong()).isZero();
        mvc.perform(get("/api/appointments/collection-queue?from=2027-01-01T00:00:00Z&to=2026-01-01T00:00:00Z")
                .header("Authorization",bearer(reception))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/appointments/collection-queue").header("Authorization",bearer(doctor))).andExpect(status().isForbidden());
        mvc.perform(get("/api/appointments/collection-queue")).andExpect(status().isUnauthorized());
        assertThatThrownBy(() -> queue.list(null,null,null,null,null,false,0,20,doctor)).isInstanceOf(ForbiddenOperationException.class);
        var noCharge=page("?state=NO_CHARGE").at("/content/0");
        assertThat(noCharge.path("hasRecordedCharge").asBoolean()).isFalse();
        assertThat(noCharge.path("pendingBalance").isNull()).isTrue();
        assertThat(noCharge.path("appointment").has("clinicalRecord")).isFalse();
        assertThat(receipts()).isZero();
    }
}

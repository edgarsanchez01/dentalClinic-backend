package com.dentalclinic.financial;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.financial.application.command.RegisterPaymentCommand;
import com.dentalclinic.financial.domain.PaymentMethod;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CollectionApiTest extends CollectionFlowSupport {
    @Test void collectionsPreserveHistoricalCheckoutIdentityAndReceiptWithoutReinterpretingThem() throws Exception {
        Long charge=administrativeCharge("500");
        Long prior=payment(charge,patientId,"100");
        jdbc.update("INSERT INTO appointment_checkout(appointment_id,occurrence_version,charge_id,payment_movement_id,"
                + "charge_disposition,actor_user_id,completed_at) VALUES (?,1,?,?,'CREATED',?,now())",scheduled,charge,prior,doctor.userId());
        jdbc.update("INSERT INTO http_idempotency(actor_user_id,operation,idempotency_key,request_hash,response_status,response_body) "
                + "VALUES (?,'APPOINTMENT_CHECKOUT','old',repeat('a',64),200,'{\"historical\":true}'::jsonb)",doctor.userId());
        var history=jdbc.queryForList("SELECT * FROM appointment_checkout");
        var receipt=jdbc.queryForMap("SELECT * FROM http_idempotency WHERE operation='APPOINTMENT_CHECKOUT'");
        postBody("new-collection",body(token(),charge,"200"),reception).andExpect(status().isOk())
                .andExpect(jsonPath("$.financialAtCollection.appliedAmount").value(300));
        assertThat(jdbc.queryForList("SELECT * FROM appointment_checkout")).isEqualTo(history);
        assertThat(jdbc.queryForMap("SELECT * FROM http_idempotency WHERE operation='APPOINTMENT_CHECKOUT'")).isEqualTo(receipt);
        assertThat(count("financial_movement")).isEqualTo(2);
    }
    @Test void partialAndSubsequentCollectionsUseSameChargeAndNeverChangeClinicalTimestamp() throws Exception {
        var before=jdbc.queryForMap("SELECT * FROM appointment WHERE id=?",scheduled);
        String initialRequest=body(token(),null,"200");
        var first=json(postBody("partial",initialRequest,reception).andExpect(status().isOk()));
        Long charge=first.at("/financialAtCollection/chargeId").asLong();
        postBody("no-new",body(token(),charge,null),reception).andExpect(status().isOk())
                .andExpect(jsonPath("$.financialAtCollection.appliedAmount").value(200))
                .andExpect(jsonPath("$.financialAtCollection.pendingBalance").value(300));
        postBody("later",body(token(),charge,"300"),owner).andExpect(status().isOk())
                .andExpect(jsonPath("$.financialAtCollection.status").value("PAGADO"));
        assertThat(count("charge")).isEqualTo(2);
        assertThat(count("financial_movement")).isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT * FROM appointment WHERE id=?",scheduled)).isEqualTo(before);
        assertThat(json(postBody("partial",initialRequest,reception)
                .andExpect(status().isOk()))).isEqualTo(first);
        assertClinicallyCompleted();
    }

    @Test void keyValidationCsrfAndCanonicalMonetaryReplay() throws Exception {
        String body=body(token(),null,null);
        mvc.perform(post(path(scheduled)).header("Authorization",bearer(reception))
                .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        for(String key: List.of(" ","a b","x".repeat(201)))
            postBody(key,body,reception).andExpect(status().isBadRequest());
        mvc.perform(post(path(scheduled)).header("Authorization",bearer(reception))
                .header("Idempotency-Key","csrf").cookie(new jakarta.servlet.http.Cookie("dentalclinic_refresh","cookie"))
                .contentType("application/json").content(body)).andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control","no-store"));
        var first=json(postBody("numeric",body,reception).andExpect(status().isOk()));
        assertThat(json(postBody("numeric",body.replace("\"expectedTotal\":500","\"expectedTotal\":500.00"),reception)
                .andExpect(status().isOk()))).isEqualTo(first);
    }

    @Test void onlyCancelledChargesAllowCreationButNeverReuseCancelledOrOrphan() throws Exception {
        Long cancelled=administrativeCharge("500");
        operations.cancelCharge(new com.dentalclinic.financial.application.command.CancelChargeCommand(
                cancelled,"Error","cancel"),owner);
        Long orphan=charge(patientId,null,"500");
        postBody("orphan",body(token(),orphan,null),reception).andExpect(status().isConflict());
        postBody("cancelled",body(token(),cancelled,null),reception).andExpect(status().isConflict());
        postBody("new",body(token(),null,null),reception).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT appointment_id FROM charge WHERE id=?",Long.class,orphan)).isNull();
        assertThat(count("financial_movement")).isZero();
    }

    @Test void reuseWorksWithMissingSnapshotAndFullyPaidCharge() throws Exception {
        Long charge=administrativeCharge("500");
        payment(charge,patientId,"500");
        jdbc.update("UPDATE appointment SET service_id=NULL,service_name=NULL,service_description=NULL,"
                + "service_reference_price=NULL,service_duration_minutes=NULL WHERE id=?",scheduled);
        postBody("paid-over",body(token(),charge,"1"),reception).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COLLECTION_PAYMENT_EXCEEDS_BALANCE"));
        postBody("paid",body(token(),charge,null),reception).andExpect(status().isOk())
                .andExpect(jsonPath("$.financialAtCollection.status").value("PAGADO"));
        assertThat(count("financial_movement")).isEqualTo(1);
    }

    @Test void corruptCancelledChargeAndExternalReversalFailClosed() throws Exception {
        Long charge=administrativeCharge("500");
        String before=token();
        Long paid=payment(charge,patientId,"100");
        reversal(paid,chargeId,patientId,"100");
        postBody("external",body(before,charge,null),reception).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FINANCIAL_CONTEXT_UNAVAILABLE"));
        assertClinicallyCompleted();
    }

    @Test void unknownEnvelopeFailsTechnicallyAndNeverReexecutes() throws Exception {
        String body=body(token(),null,null);
        postBody("durable",body,reception).andExpect(status().isOk());
        jdbc.update("UPDATE http_idempotency SET response_body=jsonb_set(response_body,'{snapshotVersion}','99'::jsonb) WHERE operation='APPOINTMENT_COLLECTION'");
        postBody("durable",body,reception).andExpect(status().isInternalServerError());
        assertThat(count("charge")).isEqualTo(2);
        assertThat(receipts()).isEqualTo(1);
        assertClinicallyCompleted();
    }

    @ParameterizedTest
    @ValueSource(strings={"NONE", "200", "500"})
    void createsWithoutClinicalMutationWithOptionalPayment(String amount) throws Exception {
        String payment = amount.equals("NONE") ? null : amount;
        var response = json(postBody("collection", body(token(), null, payment), reception)
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")));
        assertThat(response.at("/appointmentStatus").asText()).isEqualTo("COMPLETED");
        assertThat(response.at("/financialAtCollection/chargeDisposition").asText()).isEqualTo("CREATED");
        assertThat(response.at("/financialAtCollection/chargeId").asLong()).isPositive();
        assertThat(response.at("/financialAtCollection/totalAmount").decimalValue()).isEqualByComparingTo("500");
        assertThat(response.at("/financialAtCollection/appliedAmount").decimalValue())
                .isEqualByComparingTo(payment == null ? "0" : payment);
        assertThat(response.at("/financialAtCollection/pendingBalance").decimalValue())
                .isEqualByComparingTo(new BigDecimal("500").subtract(new BigDecimal(payment == null ? "0" : payment)));
        assertThat(count("financial_movement")).isEqualTo(payment == null ? 0 : 1);
        assertThat(count("appointment_checkout")).isZero();
        assertThat(count("patient_feedback")).isEqualTo(1);
        assertThat(count("notification")).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM appointment_event WHERE event_type='APPOINTMENT_COMPLETED'",
                Long.class)).isEqualTo(1);
        assertThat(receipts()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"NONE","200","300"})
    void reusePreservesExistingPayments(String amount) throws Exception {
        Long charge = administrativeCharge("500");
        operations.registerPayment(new RegisterPaymentCommand(charge,PaymentMethod.CASH,new BigDecimal("200"),null,"prior"),owner);
        var response = json(postBody("reuse",body(token(),charge,amount.equals("NONE") ? null : amount),reception)
                .andExpect(status().isOk()));
        assertThat(response.at("/financialAtCollection/chargeId").asLong()).isEqualTo(charge);
        assertThat(response.at("/financialAtCollection/chargeDisposition").asText()).isEqualTo("REUSED");
        assertThat(response.at("/financialAtCollection/appliedAmount").decimalValue())
                .isEqualByComparingTo(amount.equals("NONE") ? "200" : new BigDecimal(amount).add(new BigDecimal("200")).toString());
        assertThat(count("charge")).isEqualTo(2);
    }

    @Test void replayRecoversHistoricalResponseWithoutEffectsAndReauthorizes() throws Exception {
        String body = body(token(),null,"200");
        var first = json(postBody("lost-response",body,reception).andExpect(status().isOk()));
        Long charge = first.at("/financialAtCollection/chargeId").asLong();
        operations.registerPayment(new RegisterPaymentCommand(charge,PaymentMethod.CARD,new BigDecimal("300"),null,"later"),owner);
        assertThat(json(postBody("lost-response",body,reception).andExpect(status().isOk()))).isEqualTo(first);
        postBody("lost-response",body.replace("\"amount\":200","\"amount\":201"),reception)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COLLECTION_CONFLICT"));
        postBody("other-key",body,reception).andExpect(status().isConflict());
        assertThat(count("patient_feedback")).isEqualTo(1);
        postBody("lost-response",body,doctor).andExpect(status().isForbidden());
        jdbc.update("UPDATE users SET active=false WHERE id=?",receptionId);
        mvc.perform(post(path(scheduled)).header("Authorization",bearer(reception)).header("Idempotency-Key","lost-response")
                .contentType("application/json").content(body)).andExpect(status().isUnauthorized());
    }

    @Test void overpaymentRollsBackNewChargeAndKey() throws Exception {
        String request = body(token(),null,"500.01");
        postBody("over",request,reception).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COLLECTION_PAYMENT_EXCEEDS_BALANCE"));
        assertClinicallyCompleted();
        assertThat(count("charge")).isEqualTo(1);
        assertThat(count("charge_item")).isZero();
        assertThat(count("financial_movement")).isZero();
        assertThat(receipts()).isZero();
        postBody("over",body(token(),null,"500"),reception).andExpect(status().isOk());
    }

    @Test void rejectsAmbiguityAndNeverClaimsOrphan() throws Exception {
        Long one = administrativeCharge("500"); administrativeCharge("500");
        postBody("amb",body(token(),one,null),reception).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COLLECTION_CHARGE_AMBIGUOUS"));
        assertClinicallyCompleted();
    }

    @Test void staleTokenAfterPaymentAndReversalWithSameNetIsRejected() throws Exception {
        Long charge = administrativeCharge("500");
        String before = token();
        assertThat(token()).isEqualTo(before);
        Long payment = payment(charge,patientId,"100"); reversal(payment,charge,patientId,"100");
        assertThat(token()).isNotEqualTo(before);
        postBody("stale",body(before,charge,null),reception).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COLLECTION_CONFLICT"));
        assertClinicallyCompleted();
    }

    @ParameterizedTest @ValueSource(strings={"0","500.001","NULL"})
    void incompatibleHistoricalSnapshotRequiresAdministration(String value) throws Exception {
        if (value.equals("NULL")) {
            jdbc.update("UPDATE appointment SET service_id=NULL,service_name=NULL,service_description=NULL,"
                    + "service_reference_price=NULL,service_duration_minutes=NULL WHERE id=?",scheduled);
        } else {
            jdbc.update("UPDATE appointment SET service_reference_price=?::numeric WHERE id=?",value,scheduled);
        }
        postBody("snapshot",body(token(),null,null),reception).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COLLECTION_ECONOMIC_REFERENCE_REQUIRED"));
        assertClinicallyCompleted();
    }

    @ParameterizedTest @ValueSource(strings={"CANCELLED","SCHEDULED","NO_SHOW"})
    void rejectsTerminalAppointment(String state) throws Exception {
        jdbc.update("UPDATE appointment SET status=?,completed_at=CASE WHEN ?='COMPLETED' THEN now() ELSE NULL END WHERE id=?",state,state,scheduled);
        postBody("terminal",body(token(),null,null),reception).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COLLECTION_CONFLICT"));
        assertThat(count("appointment_checkout")).isZero();
    }

    @Test void occurrenceAndExplicitReuseAreRequired() throws Exception {
        String body = body(token(),null,null);
        postBody("occ",body.replace("\"expectedOccurrenceVersion\":1","\"expectedOccurrenceVersion\":2"),reception)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COLLECTION_CONFLICT"));
        administrativeCharge("500");
        postBody("reuse-required",body(token(),null,null),reception).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COLLECTION_CONFLICT"));
    }

    @Test void roleAndOwnershipSecurityAndAdministrativeRoutesRemainRestricted() throws Exception {
        String body=body(token(),null,null);
        for (var actor : List.of(doctor,
                new AuthenticatedUser(jdbc.queryForObject("SELECT user_id FROM patient WHERE id=?",Long.class,patientId),Role.PATIENT))) {
            postBody("forbid",body,actor).andExpect(status().isForbidden()).andExpect(header().string("Cache-Control","no-store"));
            assertThatThrownBy(() -> collections.collect(collectionParser.parse(scheduled,"direct",body),actor))
                    .isInstanceOf(com.dentalclinic.identity.domain.exception.ForbiddenOperationException.class);
        }
        postBody("owner-unassigned",body,owner).andExpect(status().isOk());
        Long otherUser=jdbc.queryForObject("SELECT user_id FROM dentist WHERE id=?",Long.class,otherDentistId);
        postBody("alien",body,new AuthenticatedUser(otherUser,Role.DENTIST)).andExpect(status().isForbidden());
        mvc.perform(post("/api/charges").header("Authorization",bearer(doctor))
                .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post(path(scheduled)).contentType("application/json").content(body))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"));
    }

    @Test void ownerCanCollectAndDentistWithoutProfileCannotEnumerate() throws Exception {
        jdbc.update("UPDATE appointment SET dentist_id=(SELECT id FROM dentist WHERE user_id=?) WHERE id=?",ownerId,scheduled);
        String ownerToken=contexts.contextFor(scheduled,owner).financialContextToken();
        postBody("owner",body(ownerToken,null,null),owner).andExpect(status().isOk());
        jdbc.update("DELETE FROM dentist WHERE id=?",otherDentistId);
        Long noProfile=jdbc.queryForObject("SELECT id FROM users WHERE email='other@checkout.test'",Long.class);
        var actor=new AuthenticatedUser(noProfile,Role.DENTIST);
        postBody("no-profile",body(ownerToken,null,null),actor).andExpect(status().isForbidden());
        mvc.perform(post(path(99999999L)).header("Authorization",bearer(actor)).header("Idempotency-Key","none")
                .contentType("application/json").content(body(ownerToken,null,null))).andExpect(status().isForbidden());
    }

    @ParameterizedTest @ValueSource(strings={"unknown","false","stringAmount","floatId","extraPayment","duplicate","null","trailing","zero","nullPayment","nullChargeId","enumNumber"})
    void strictRequestContract(String mode) throws Exception {
        String body=body(token(),null,"200");
        body=switch(mode) {
            case "unknown" -> body.replace("\"charge\":","\"patientId\":1,\"charge\":");
            case "false" -> body.replace("true","false");
            case "stringAmount" -> body.replace("\"amount\":200","\"amount\":\"200\"");
            case "floatId" -> body.replace("\"expectedOccurrenceVersion\":1","\"expectedOccurrenceVersion\":1.5");
            case "extraPayment" -> body.replace("\"action\":\"REGISTER\"","\"action\":\"NO_NEW_PAYMENT\"");
            case "duplicate" -> body.replace("\"confirmed\":true","\"confirmed\":false,\"confirmed\":true");
            case "null" -> "null";
            case "trailing" -> body+" {}";
            case "nullPayment" -> body(token(),null,null).replace("\"NO_NEW_PAYMENT\"","\"NO_NEW_PAYMENT\",\"method\":null");
            case "nullChargeId" -> body.replace("\"CREATE_FROM_APPOINTMENT\"","\"CREATE_FROM_APPOINTMENT\",\"chargeId\":null");
            case "enumNumber" -> body.replace("\"CREATE_FROM_APPOINTMENT\"","0");
            default -> body.replace("\"amount\":200","\"amount\":0");
        };
        postBody("strict",body,reception).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COLLECTION_INVALID_REQUEST"));
        assertClinicallyCompleted();
    }
}

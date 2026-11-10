package com.dentalclinic.checkout;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.http.MediaType;
import com.dentalclinic.checkout.api.AppointmentCheckoutRequestParser;
import com.dentalclinic.checkout.application.*;
import com.dentalclinic.financial.application.*;
import com.dentalclinic.financial.application.command.*;
import com.dentalclinic.identity.application.UserService;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.infrastructure.security.*;
import com.fasterxml.jackson.databind.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@AutoConfigureMockMvc
public abstract class CheckoutFlowSupport extends CheckoutIntegrationSupport {
    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected CheckoutCoordinator coordinator;
    @Autowired protected AppointmentCheckoutRequestParser parser;
    @Autowired protected AppointmentFinancialContextService contexts;
    @Autowired protected FinancialOperations operations;
    @Autowired protected Clock clock;
    @Autowired private UserService users;
    @Autowired private JwtTokenService jwt;
    protected AuthenticatedUser doctor;
    protected AuthenticatedUser owner;
    protected Long scheduled;
    protected Long serviceId;

    @Override
    @BeforeEach
    protected void seedFixture() throws Exception {
        for (String table : List.of("communication_intention", "communication", "notification",
                "patient_feedback", "appointment_event", "dentist_schedule", "clinic_schedule", "clinic")) jdbc.update("DELETE FROM " + table);
        super.seedFixture();
        Long doctorUser = jdbc.queryForObject("SELECT user_id FROM dentist WHERE id=?", Long.class, dentistId);
        doctor = new AuthenticatedUser(doctorUser, Role.DENTIST);
        owner = new AuthenticatedUser(ownerId, Role.OWNER);
        jdbc.update("INSERT INTO dentist(user_id,first_name,last_name,phone) VALUES (?, 'Owner', 'Test', '600000305')", ownerId);
        Long patientUser = jdbc.queryForObject("INSERT INTO users(email,password_hash,role,active) "
                + "VALUES ('patient@checkout.test','test','PATIENT',true) RETURNING id", Long.class);
        jdbc.update("UPDATE patient SET user_id=?, email='patient@checkout.test' WHERE id=?", patientUser, patientId);
        serviceId = jdbc.queryForObject("INSERT INTO service_catalog_item(name,reference_price,duration_minutes,active) "
                + "VALUES ('Consulta historica',500,60,false) RETURNING id", Long.class);
        scheduled = appointment(patientId, dentistId, "SCHEDULED");
        jdbc.update("UPDATE appointment SET service_id=?,service_name='Consulta historica',"
                + "service_description='Snapshot',service_reference_price=500,service_duration_minutes=60 WHERE id=?",
                serviceId, scheduled);
    }

    protected String path(Long id) { return "/api/appointments/" + id + "/checkout"; }
    protected String token() { return contexts.contextFor(scheduled, doctor).financialContextToken(); }
    protected String bearer(AuthenticatedUser actor) {
        return "Bearer " + jwt.issue(users.getById(actor.userId()), clock.instant()).value();
    }
    protected String body(String token, Long reuse, String payment) {
        return "{\"expectedOccurrenceVersion\":1,\"financialContextToken\":\"" + token
                + "\",\"charge\":{\"action\":\"" + (reuse == null ? "CREATE_FROM_APPOINTMENT" : "REUSE")
                + "\"," + (reuse == null ? "" : "\"chargeId\":" + reuse + ",")
                + "\"expectedTotal\":500,\"confirmed\":true},\"payment\":"
                + (payment == null ? "{\"action\":\"NO_NEW_PAYMENT\"}" :
                        "{\"action\":\"REGISTER\",\"method\":\"CASH\",\"amount\":" + payment + "}") + "}";
    }
    protected ResultActions postBody(String key, String body, AuthenticatedUser actor) throws Exception {
        return mvc.perform(post(path(scheduled)).header("Authorization", bearer(actor))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }
    protected CheckoutCommand command(String key, Long reuse, String payment) {
        return parser.parse(scheduled, key, body(token(), reuse, payment));
    }
    protected Long administrativeCharge(String amount) {
        return operations.confirmCharge(new ConfirmChargeCommand(patientId, scheduled,
                List.of(new ChargeItemData(null, dentistId, "Cargo acordado", 1, new BigDecimal(amount))),
                null, java.util.UUID.randomUUID().toString()), owner).getId();
    }
    protected JsonNode json(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
    protected void assertNoCheckout() {
        org.assertj.core.api.Assertions.assertThat(count("appointment_checkout")).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT status FROM appointment WHERE id=?",
                String.class, scheduled)).isEqualTo("SCHEDULED");
        org.assertj.core.api.Assertions.assertThat(count("patient_feedback")).isZero();
    }
}

package com.dentalclinic.financial;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import com.dentalclinic.checkout.CheckoutFlowSupport;
import com.dentalclinic.appointment.application.AppointmentService;
import com.dentalclinic.financial.api.CollectionRequestParser;
import com.dentalclinic.financial.application.CollectionCommand;
import com.dentalclinic.financial.application.CollectionCoordinator;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import static org.assertj.core.api.Assertions.assertThat;

/** Real clinical finalization precedes each administrative collection test. */
public abstract class CollectionFlowSupport extends CheckoutFlowSupport {
    @Autowired protected AppointmentService appointments;
    @Autowired protected CollectionCoordinator collections;
    @Autowired protected CollectionRequestParser collectionParser;
    protected AuthenticatedUser reception;

    @Override @BeforeEach
    protected void seedFixture() throws Exception {
        super.seedFixture();
        reception = new AuthenticatedUser(receptionId, Role.RECEPTIONIST);
        appointments.complete(scheduled, doctor, 1, "clinical-fixture");
    }

    @Override protected String path(Long id) { return "/api/appointments/" + id + "/collection"; }
    protected CollectionCommand collectionCommand(String key, Long charge, String payment) {
        return collectionParser.parse(scheduled, key, body(token(), charge, payment));
    }
    protected long receipts() {
        return jdbc.queryForObject("SELECT count(*) FROM http_idempotency WHERE operation='APPOINTMENT_COLLECTION'", Long.class);
    }
    protected void assertClinicallyCompleted() {
        assertThat(jdbc.queryForObject("SELECT status FROM appointment WHERE id=?", String.class, scheduled)).isEqualTo("COMPLETED");
        assertThat(count("appointment_checkout")).isZero();
        assertThat(count("patient_feedback")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM appointment_event WHERE event_type='APPOINTMENT_COMPLETED'", Long.class)).isEqualTo(1);
    }
}

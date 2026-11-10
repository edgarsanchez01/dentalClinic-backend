package com.dentalclinic.checkout.application;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import com.dentalclinic.checkout.CheckoutIntegrationSupport;
import com.dentalclinic.checkout.domain.*;
import com.dentalclinic.checkout.infrastructure.repository.AppointmentCheckoutRepository;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;

/** UX-05C replaces the registrar's old write contract. DDL invariants remain in CheckoutSchemaMigrationTest. */
class AppointmentCheckoutRegistrarIntegrationTest extends CheckoutIntegrationSupport {
    @Autowired private AppointmentCheckoutRegistrar registrar;
    @Autowired private AppointmentCheckoutService service;
    @Autowired private AppointmentCheckoutRepository checkouts;

    @Test void everyApplicationWriterIsRetired() {
        var draft = new AppointmentCheckoutRegistrar.CheckoutDraft(appointmentId,1,chargeId,null,
                ChargeDisposition.CREATED,ownerId,Instant.now());
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(s -> registrar.register(draft)))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.checkout(null,null)).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> checkouts.save(AppointmentCheckout.create(appointmentId,1,chargeId,null,
                ChargeDisposition.CREATED,ownerId,Instant.now()))).isInstanceOf(ForbiddenOperationException.class);
        assertThat(count("appointment_checkout")).isZero();
    }

    @Test void historicalIdentityIsReadExactlyWithoutReinterpretingIt() {
        var now = Instant.parse("2026-10-22T12:00:00Z");
        Long payment = payment(chargeId,patientId,"40");
        Long id = jdbc.queryForObject("INSERT INTO appointment_checkout(appointment_id,occurrence_version,charge_id,"
                + "payment_movement_id,charge_disposition,actor_user_id,completed_at) VALUES (?,1,?,?,'REUSED',?,?) RETURNING id",
                Long.class,appointmentId,chargeId,payment,receptionId,java.sql.Timestamp.from(now));
        var before = jdbc.queryForMap("SELECT * FROM appointment_checkout WHERE id=?",id);
        var value = checkouts.findById(id).orElseThrow();
        assertThat(value.getId()).isEqualTo(id);
        assertThat(value.getAppointmentId()).isEqualTo(appointmentId);
        assertThat(value.getOccurrenceVersion()).isEqualTo(1);
        assertThat(value.getChargeId()).isEqualTo(chargeId);
        assertThat(value.getPaymentMovementId()).isEqualTo(payment);
        assertThat(value.getChargeDisposition()).isEqualTo(ChargeDisposition.REUSED);
        assertThat(value.getActorUserId()).isEqualTo(receptionId);
        assertThat(value.getCompletedAt()).isEqualTo(now);
        assertThat(checkouts.findByAppointmentId(appointmentId).orElseThrow().getId()).isEqualTo(id);
        assertThat(jdbc.queryForMap("SELECT * FROM appointment_checkout WHERE id=?",id)).isEqualTo(before);
        assertThat(checkouts.findById(-1L)).isEmpty();
    }
}

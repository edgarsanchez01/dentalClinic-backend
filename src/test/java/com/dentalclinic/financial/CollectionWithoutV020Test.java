package com.dentalclinic.financial;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import com.dentalclinic.checkout.infrastructure.repository.AppointmentCheckoutRepository;
import static org.assertj.core.api.Assertions.*;

/** Validate the complete application against the real V019 schema, with no V020 at all. */
@TestPropertySource(properties = {
        "spring.sql.init.schema-locations=file:../db/schema/V001__baseline.sql,"
        + "file:../db/schema/V002__identity_access.sql,"
        + "file:../db/schema/V003__clinic_onboarding.sql,"
        + "file:../db/schema/V004__patients_clinical.sql,"
        + "file:../db/schema/V005__scheduling.sql,"
        + "file:../db/schema/V006__appointments.sql,"
        + "file:../db/schema/V007__service_catalog.sql,"
        + "file:../db/schema/V008__appointment_service_snapshot.sql,"
        + "file:../db/schema/V009__treatments.sql,"
        + "file:../db/schema/V010__odontogram.sql,"
        + "file:../db/schema/V011__odontogram_surfaces.sql,"
        + "file:../db/schema/V012__treatment_evolution.sql,"
        + "file:../db/schema/V013__clinical_appointment_context.sql,"
        + "file:../db/schema/V014__clinical_corrections.sql,"
        + "file:../db/schema/V015__odontogram_history_context.sql,"
        + "file:../db/schema/V016__financial_module.sql,"
        + "file:../db/schema/V017__patient_feedback.sql,"
        + "file:../db/schema/V018__identity_email_outbox.sql,"
        + "file:../db/schema/V019__patient_medical_intake.sql"
})
class CollectionWithoutV020Test extends CollectionFlowSupport {
    @Autowired private AppointmentCheckoutRepository history;

    @Test void clinicalCompletionAndCollectionWorkWithoutCreatingOrRequiringHistoricalTable() {
        assertThat(jdbc.queryForObject("SELECT to_regclass('appointment_checkout') IS NULL",Boolean.class)).isTrue();
        assertThat(history.findByAppointmentId(scheduled)).isEmpty();
        var command=collectionCommand("no-v020",null,"200");
        var first=collections.collect(command,reception);
        assertThat(collections.collect(command,reception)).isEqualTo(first);
        assertThat(first.financialAtCollection().pendingBalance()).isEqualByComparingTo("300");
        assertThat(jdbc.queryForObject("SELECT to_regclass('appointment_checkout') IS NULL",Boolean.class)).isTrue();
    }
}

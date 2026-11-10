package com.dentalclinic.odontogram.api;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;
import com.dentalclinic.appointment.integration.AppointmentIntegrationTestSupport;
import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.appointment.application.AppointmentClinicalContextService.Selection;
import com.dentalclinic.clinical.domain.*;
import com.dentalclinic.clinical.infrastructure.repository.TreatmentRepository;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.odontogram.application.OdontogramService;
import com.dentalclinic.odontogram.domain.*;

/** SQL real V001–V015, isolated TC database with tmpfs; no external datasource or patient data. */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:16:///dentalclinic_odontogram_e2?TC_TMPFS=/var/lib/postgresql/data:rw"
})
abstract class OdontogramHistoryTestSupport extends AppointmentIntegrationTestSupport {
    @Autowired protected OdontogramService odontogram;
    @Autowired protected TreatmentRepository treatmentRepository;
    @Autowired private DataSource dataSource;
    @Autowired private Environment environment;
    protected String token;

    @BeforeEach void verifyIsolationAndAuthenticate() throws Exception {
        assertThat(environment.getProperty("spring.datasource.url"))
                .matches("jdbc:tc:postgresql:16:///dentalclinic_odontogram_e2(_concurrency|_explain)?\\?TC_TMPFS=/var/lib/postgresql/data:rw");
        assertThat(environment.getProperty("spring.datasource.driver-class-name"))
                .isEqualTo("org.testcontainers.jdbc.ContainerDatabaseDriver");
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).doesNotContain(":5434/");
            // The JDBC TC driver's logical URL keys the private container; its physical DB is 'test'.
            assertThat(jdbcTemplate.queryForObject("SELECT current_database()", String.class)).isEqualTo("test");
            System.out.println("E2 TC isolation: " + connection.getMetaData().getURL() + ", configured tmpfs, no external datasource");
        }
        token = bearer(loginToken(dentistUser));
    }
    protected AuthenticatedUser actor() { return new AuthenticatedUser(dentistUser.getId(), Role.DENTIST); }
    protected String path() { return "/api/patients/" + patient.getId() + "/odontogram"; }
    protected Long appointment(int hour) {
        return appointmentRepository.saveAndFlush(Appointment.create(patient.getId(), dentist.getId(), owner.getId(),
                at(MARTES, hour, 0), at(MARTES, hour + 1, 0), 60, "Fixture E2", null)).getId();
    }
    protected Long treatment() {
        return treatmentRepository.saveAndFlush(Treatment.create(patient.getId(), null, "Fixture E2",
                null, TreatmentStatus.PLANNED, null, null, TestTime.now())).getId();
    }
    protected Long createTooth(String notes, List<Long> treatments, Selection selection) {
        return odontogram.create(patient.getId(), new OdontogramService.ToothData(21, ToothCondition.HEALTHY,
                notes, treatments), actor(), selection).record().getId();
    }
    protected String body(ToothCondition condition, String notes, List<Long> treatments, Selection selection) throws Exception {
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("condition", condition); data.put("notes", notes); data.put("treatmentIds", treatments);
        if (selection != null) data.put("appointmentContext", java.util.Map.of(
                "appointmentId", selection.appointmentId(), "occurrenceVersion", selection.occurrenceVersion()));
        return objectMapper.writeValueAsString(data);
    }
}

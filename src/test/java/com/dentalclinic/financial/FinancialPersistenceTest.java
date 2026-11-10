package com.dentalclinic.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.dentalclinic.financial.application.ChargeFinancialStateService;
import com.dentalclinic.financial.application.ChargeReferenceValidator;
import com.dentalclinic.financial.application.FinancialAuthorizationService;
import com.dentalclinic.financial.application.command.ChargeItemData;
import com.dentalclinic.financial.application.command.ConfirmChargeCommand;
import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.ChargeItem;
import com.dentalclinic.financial.domain.FinancialMovement;
import com.dentalclinic.financial.domain.ChargeStatus;
import com.dentalclinic.financial.domain.InvalidChargeException;
import com.dentalclinic.financial.domain.PaymentMethod;
import com.dentalclinic.financial.infrastructure.repository.ChargeItemRepository;
import com.dentalclinic.financial.infrastructure.repository.ChargeRepository;
import com.dentalclinic.financial.infrastructure.repository.FinancialMovementRepository;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/**
 * Persistencia real del modulo financiero sobre PostgreSQL 16 efimero (Testcontainers) con el DDL
 * aprobado V001..V016 y Hibernate en {@code validate}. Comprueba:
 *
 * <ul>
 *   <li>Aplicacion real de V016 y mapeo de {@link Charge}, {@link ChargeItem} y
 *       {@link FinancialMovement}.</li>
 *   <li>Conversion {@code MovementDirection IN/OUT <-> 'I'/'O'} en base.</li>
 *   <li>Append-only: los repositorios no exponen borrado; las FKs {@code ON DELETE RESTRICT}
 *       y {@code UNIQUE(reverses_movement_id)} actuan de verdad.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:16:///dentalclinic_finance",
        "dentalclinic.communication.email.enabled=false",
        "dentalclinic.communication.dispatch.enabled=false",
        "dentalclinic.appointment.reminder.enabled=false",
        "dentalclinic.appointment.reminder.email-delivery-enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=always",
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
                + "file:../db/schema/V019__patient_medical_intake.sql,"
                + "file:../db/schema/V020__appointment_checkout.sql"
})
class FinancialPersistenceTest {

    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
    private static final String ISOLATED_URL = "jdbc:tc:postgresql:16:///dentalclinic_finance";

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        // Prioridad superior a variables de entorno/JVM, antes de inicializar DDL o JPA.
        registry.add("spring.datasource.url", () -> ISOLATED_URL);
        registry.add("spring.datasource.driver-class-name",
                () -> "org.testcontainers.jdbc.ContainerDatabaseDriver");
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ChargeRepository chargeRepository;
    @Autowired
    private ChargeItemRepository chargeItemRepository;
    @Autowired
    private FinancialMovementRepository movementRepository;
    @Autowired
    private FinancialAuthorizationService authorization;
    @Autowired
    private ChargeReferenceValidator references;
    @Autowired
    private ChargeFinancialStateService states;
    @Autowired
    private Environment environment;

    private Long ownerId;
    private Long patientId;

    @BeforeEach
    void seed() throws Exception {
        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo(ISOLATED_URL);
        assertThat(environment.getProperty("spring.datasource.driver-class-name"))
                .isEqualTo("org.testcontainers.jdbc.ContainerDatabaseDriver");
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getURL()).doesNotContain(":5434/");
        }
        jdbc.update("DELETE FROM appointment_checkout");
        jdbc.update("DELETE FROM financial_movement");
        jdbc.update("DELETE FROM charge_item");
        jdbc.update("DELETE FROM charge");
        jdbc.update("DELETE FROM appointment");
        jdbc.update("DELETE FROM dentist");
        jdbc.update("DELETE FROM patient");
        jdbc.update("DELETE FROM users");
        ownerId = jdbc.queryForObject(
                "INSERT INTO users (email, password_hash, role, active, created_at) "
                        + "VALUES ('fin@c.com', 'x', 'OWNER', TRUE, now()) RETURNING id",
                Long.class);
        patientId = jdbc.queryForObject(
                "INSERT INTO patient (first_name, last_name, phone, created_at) "
                        + "VALUES ('Pago', 'Prueba', '600000099', now()) RETURNING id",
                Long.class);
    }

    @Test
    void v016AplicaYPersisteLasTresTablas() {
        assertThat(tableNames()).contains("charge", "charge_item", "financial_movement");

        Charge charge = chargeRepository.save(
                Charge.createConfirmed(patientId, null, new BigDecimal("1000.00"), null, ownerId, NOW));
        ChargeItem item = chargeItemRepository.save(
                ChargeItem.create(charge.getId(), null, null, "Limpieza", 2, new BigDecimal("500.00"), NOW));
        FinancialMovement payment = movementRepository.save(FinancialMovement.payment(
                charge.getId(), patientId, PaymentMethod.CASH, new BigDecimal("500.00"), null, ownerId, NOW));

        assertThat(charge.getId()).isNotNull();
        assertThat(item.getLineTotal()).isEqualByComparingTo("1000.00");
        assertThat(payment.getId()).isNotNull();
    }

    @Test
    void directionSePersisteComoLetra_PaymentIO_ReversalO() {
        Charge charge = chargeRepository.save(
                Charge.createConfirmed(patientId, null, new BigDecimal("1000.00"), null, ownerId, NOW));
        FinancialMovement payment = movementRepository.save(FinancialMovement.payment(
                charge.getId(), patientId, PaymentMethod.TRANSFER, new BigDecimal("300.00"), null, ownerId, NOW));
        FinancialMovement reversal = movementRepository.save(FinancialMovement.reversal(
                charge.getId(), patientId, payment.getId(), new BigDecimal("300.00"), "Duplicado", ownerId, NOW));

        assertThat(rawDirection(payment.getId())).isEqualTo("I");
        assertThat(rawDirection(reversal.getId())).isEqualTo("O");
        assertThat(movementRepository.findByChargeIdOrderByPerformedAtAscIdAsc(charge.getId()))
                .extracting(FinancialMovement::getDirection)
                .containsExactly(com.dentalclinic.financial.domain.MovementDirection.IN,
                        com.dentalclinic.financial.domain.MovementDirection.OUT);
    }

    @Test
    void repositoriosNoExponenBorrado() {
        for (Class<?> type : List.of(ChargeRepository.class, ChargeItemRepository.class,
                FinancialMovementRepository.class)) {
            assertThat(Arrays.stream(type.getMethods()).map(Method::getName))
                    .as("%s no expone borrado", type.getSimpleName())
                    .doesNotContain("delete", "deleteById", "deleteAll", "deleteAllInBatch", "saveAll");
        }
    }

    @Test
    void unMovimientoAdmiteUnaSolaReversa() {
        Charge charge = chargeRepository.save(
                Charge.createConfirmed(patientId, null, new BigDecimal("1000.00"), null, ownerId, NOW));
        FinancialMovement payment = movementRepository.save(FinancialMovement.payment(
                charge.getId(), patientId, PaymentMethod.CARD, new BigDecimal("500.00"), null, ownerId, NOW));
        movementRepository.save(FinancialMovement.reversal(
                charge.getId(), patientId, payment.getId(), new BigDecimal("500.00"), "motivo", ownerId, NOW));

        assertThat(existsReversal(payment.getId())).isTrue();
        assertThatThrownBy(() -> movementRepository.save(FinancialMovement.reversal(
                charge.getId(), patientId, payment.getId(), new BigDecimal("500.00"), "otra", ownerId, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void noSePuedeBorrarUnCargoConConceptosNiUnMovimientoReferenciado() {
        Charge charge = chargeRepository.save(
                Charge.createConfirmed(patientId, null, new BigDecimal("1000.00"), null, ownerId, NOW));
        chargeItemRepository.save(
                ChargeItem.create(charge.getId(), null, null, "Conducto", 1, new BigDecimal("1000.00"), NOW));

        assertThatThrownBy(() -> jdbc.update("DELETE FROM charge WHERE id = ?", charge.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sumaAplicadaExcluyePagosRevertidosYNoCuentaLaReversaComoIngreso() {
        Charge charge = chargeRepository.save(
                Charge.createConfirmed(patientId, null, new BigDecimal("1000.00"), null, ownerId, NOW));

        assertThat(movementRepository.sumValidAppliedAmount(charge.getId())).isEmpty();

        FinancialMovement valid = movementRepository.save(FinancialMovement.payment(
                charge.getId(), patientId, PaymentMethod.CASH, new BigDecimal("300.00"), null,
                ownerId, NOW));
        FinancialMovement reversed = movementRepository.save(FinancialMovement.payment(
                charge.getId(), patientId, PaymentMethod.CARD, new BigDecimal("200.00"), null,
                ownerId, NOW));
        movementRepository.save(FinancialMovement.reversal(
                charge.getId(), patientId, reversed.getId(), new BigDecimal("200.00"), "Duplicado",
                ownerId, NOW));

        assertThat(valid.getId()).isNotNull();
        assertThat(movementRepository.sumValidAppliedAmount(charge.getId()))
                .get()
                .satisfies(amount -> assertThat(amount).isEqualByComparingTo("300.00"));
        assertThat(states.stateOf(charge.getId(), owner()).status()).isEqualTo(ChargeStatus.PARCIAL);
        assertThat(states.stateOf(charge.getId(), owner()).pendingBalance()).isEqualByComparingTo("700.00");
        assertThat(jdbc.queryForObject("SELECT amount FROM financial_movement WHERE id = ?",
                BigDecimal.class, reversed.getId())).isEqualByComparingTo("200.00");
    }

    @Test
    void reversaCompletaReabreCargoPagadoSinModificarElPagoOriginal() {
        Charge charge = savedCharge(null);
        FinancialMovement payment = movementRepository.save(FinancialMovement.payment(
                charge.getId(), patientId, PaymentMethod.CASH, charge.getTotalAmount(), null, ownerId, NOW));
        assertThat(states.stateOf(charge.getId(), owner()).status()).isEqualTo(ChargeStatus.PAGADO);

        FinancialMovement reversal = movementRepository.save(FinancialMovement.reversal(
                payment.getChargeId(), payment.getPatientId(), payment.getId(), payment.getAmount(),
                "Duplicado", ownerId, NOW));

        assertThat(reversal.getAmount()).isEqualByComparingTo(payment.getAmount());
        assertThat(movementRepository.sumValidAppliedAmount(charge.getId())).isEmpty();
        assertThat(states.stateOf(charge.getId(), owner()).status()).isEqualTo(ChargeStatus.PENDIENTE);
        assertThat(states.stateOf(charge.getId(), owner()).pendingBalance()).isEqualByComparingTo("1000.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM financial_movement WHERE id = ? AND type = 'PAYMENT'",
                Long.class, payment.getId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT amount FROM financial_movement WHERE id = ?",
                BigDecimal.class, payment.getId())).isEqualByComparingTo("1000.00");
    }

    @Test
    void dentistConsultaPeroNoCreaCargosNiRegistraPagosAunqueSeanPropios() {
        Professional professional = savedProfessional("own@finance.test");
        Long appointmentId = savedAppointment(professional.dentistId());
        Charge charge = savedCharge(appointmentId);
        savedItem(charge.getId(), professional.dentistId(), "1000.00");
        long chargesBefore = jdbc.queryForObject("SELECT COUNT(*) FROM charge", Long.class);
        long movementsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM financial_movement", Long.class);

        authorization.requireView(professional.actor(), charge);
        assertThat(states.stateOf(charge.getId(), professional.actor()).status()).isEqualTo(ChargeStatus.PENDIENTE);

        assertThatThrownBy(() -> authorization.requireRegisterPayment(professional.actor(), charge))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> authorization.requireCreateCharge(professional.actor(), new ConfirmChargeCommand(
                patientId, appointmentId,
                List.of(new ChargeItemData(null, null, "Consulta", 1, BigDecimal.TEN)), null, "scope")))
                .isInstanceOf(ForbiddenOperationException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM charge", Long.class)).isEqualTo(chargesBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM financial_movement", Long.class)).isEqualTo(movementsBefore);
        assertThat(jdbc.queryForObject("SELECT status FROM appointment WHERE id = ?", String.class,
                appointmentId)).isEqualTo("COMPLETED");
    }

    @Test
    void dentistNoAccedeParcialmenteACargoMixtoAunqueSeaSuCita() {
        Professional own = savedProfessional("own@finance.test");
        Professional other = savedProfessional("other@finance.test");
        Long appointmentId = savedAppointment(own.dentistId());
        Charge charge = savedCharge(appointmentId);
        savedItem(charge.getId(), own.dentistId(), "500.00");
        savedItem(charge.getId(), other.dentistId(), "500.00");

        assertThatThrownBy(() -> states.stateOf(charge.getId(), own.actor())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> authorization.requireRegisterPayment(own.actor(), charge))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThat(states.stateOf(charge.getId(), owner()).status()).isEqualTo(ChargeStatus.PENDIENTE);
    }

    @Test
    void dentistNoUsaTreatingDentistIdParaAccederACargosSinCitaODeOtraCita() {
        Professional own = savedProfessional("own@finance.test");
        Professional other = savedProfessional("other@finance.test");
        for (Long appointmentId : Arrays.asList(null, savedAppointment(other.dentistId()))) {
            Charge charge = savedCharge(appointmentId);
            savedItem(charge.getId(), own.dentistId(), "1000.00");
            assertThatThrownBy(() -> states.stateOf(charge.getId(), own.actor()))
                    .isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> authorization.requireRegisterPayment(own.actor(), charge))
                    .isInstanceOf(ForbiddenOperationException.class);
            assertThatThrownBy(() -> authorization.requireCreateCharge(own.actor(), new ConfirmChargeCommand(
                    patientId, appointmentId,
                    List.of(new ChargeItemData(null, own.dentistId(), "Consulta", 1, BigDecimal.TEN)), null, "scope")))
                    .isInstanceOf(ForbiddenOperationException.class);
        }
    }

    @Test
    void referenciasPersistidasImpidenUsarCitaDeOtroPaciente() {
        Professional professional = savedProfessional("own@finance.test");
        Long appointmentId = savedAppointment(professional.dentistId());
        Long otherPatient = jdbc.queryForObject("INSERT INTO patient (first_name, last_name, phone, created_at) "
                + "VALUES ('Otro', 'Prueba', '600000100', now()) RETURNING id", Long.class);

        assertThatThrownBy(() -> references.requireValidReferences(otherPatient, appointmentId, owner()))
                .isInstanceOf(InvalidChargeException.class);
        assertThatThrownBy(() -> authorization.requireCreateCharge(professional.actor(), new ConfirmChargeCommand(
                otherPatient, appointmentId,
                List.of(new ChargeItemData(null, professional.dentistId(), "Consulta", 1, BigDecimal.TEN)), null, "scope")))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void consultaNuncaExponeSaldoAlPatient() {
        Charge charge = savedCharge(null);

        assertThatThrownBy(() -> states.stateOf(charge.getId(), new AuthenticatedUser(ownerId, Role.PATIENT)))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    private Charge savedCharge(Long appointmentId) {
        return chargeRepository.save(Charge.createConfirmed(patientId, appointmentId,
                new BigDecimal("1000.00"), null, ownerId, NOW));
    }

    private void savedItem(Long chargeId, Long dentistId, String amount) {
        chargeItemRepository.save(ChargeItem.create(chargeId, null, dentistId, "Consulta", 1,
                new BigDecimal(amount), NOW));
    }

    private Professional savedProfessional(String email) {
        Long userId = jdbc.queryForObject("INSERT INTO users (email, password_hash, role, active, created_at) "
                + "VALUES (?, 'x', 'DENTIST', TRUE, now()) RETURNING id", Long.class, email);
        Long dentistId = jdbc.queryForObject("INSERT INTO dentist (user_id, first_name, last_name, phone, created_at) "
                + "VALUES (?, 'Profesional', 'Prueba', '600000101', now()) RETURNING id", Long.class, userId);
        return new Professional(dentistId, new AuthenticatedUser(userId, Role.DENTIST));
    }

    private Long savedAppointment(Long dentistId) {
        // Fixture sintetica exclusivamente en Testcontainers; no ejecuta servicios de escritura de citas.
        return jdbc.queryForObject("INSERT INTO appointment (patient_id, dentist_id, created_by_user_id, "
                + "start_at, end_at, duration_minutes, status, completed_at) "
                + "VALUES (?, ?, ?, '2026-10-14T00:00:00Z', '2026-10-14T01:00:00Z', 60, "
                + "'COMPLETED', '2026-10-14T01:00:00Z') RETURNING id", Long.class, patientId, dentistId, ownerId);
    }

    private AuthenticatedUser owner() {
        return new AuthenticatedUser(ownerId, Role.OWNER);
    }

    private record Professional(Long dentistId, AuthenticatedUser actor) { }

    private String rawDirection(Long movementId) {
        return jdbc.queryForObject(
                "SELECT direction FROM financial_movement WHERE id = ?", String.class, movementId);
    }

    private boolean existsReversal(Long originalId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM financial_movement WHERE reverses_movement_id = ?)",
                Boolean.class, originalId));
    }

    private List<String> tableNames() {
        return jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' "
                        + "AND table_type = 'BASE TABLE'",
                String.class);
    }
}

package com.dentalclinic.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.dentalclinic.financial.application.FinancialReportService;
import com.dentalclinic.financial.application.command.CancelChargeCommand;
import com.dentalclinic.financial.application.command.RegisterPaymentCommand;
import com.dentalclinic.financial.application.command.ReversePaymentCommand;
import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.ChargeStatus;
import com.dentalclinic.financial.domain.FinancialMovement;
import com.dentalclinic.financial.domain.PaymentMethod;
import com.dentalclinic.identity.application.UserService;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.identity.infrastructure.security.JwtTokenService;

/**
 * Reportes financieros de Fase 4C contra PostgreSQL 16 efímero (V001..V016) y MockMvc con los
 * filtros de seguridad reales: agregación SQL (incluida la atribución de reversas), separación de
 * períodos por fecha de movimiento, filtros de cargos y autorización por rol.
 */
class FinancialReportIntegrationTest extends FinancialOperationsIntegrationSupport {

    private static final String P1_FROM = "2026-03-01T00:00:00Z";
    private static final String P2_FROM = "2026-04-01T00:00:00Z";
    private static final String P3_FROM = "2026-05-01T00:00:00Z";

    @Autowired private FinancialReportService reportService;
    @Autowired private MockMvc mvc;
    @Autowired private UserService users;
    @Autowired private JwtTokenService jwt;
    @Autowired private Clock clock;

    private String token(AuthenticatedUser actor) {
        return jwt.issue(users.getById(actor.userId()), clock.instant()).value();
    }

    private FinancialMovement pay(Charge charge, PaymentMethod method, String amount) {
        return operations.registerPayment(
                new RegisterPaymentCommand(charge.getId(), method, new BigDecimal(amount), null, key()), owner);
    }

    private FinancialMovement reverse(Charge charge, FinancialMovement payment) {
        return operations.reversePayment(
                new ReversePaymentCommand(charge.getId(), payment.getId(), "Compensación sintética", key()), owner);
    }

    private void performedAt(Long movementId, String instant) {
        jdbc.update("UPDATE financial_movement SET performed_at = ?::timestamptz WHERE id = ?", instant, movementId);
    }

    private void confirmedAt(Long chargeId, String instant) {
        jdbc.update("UPDATE charge SET confirmed_at = ?::timestamptz WHERE id = ?", instant, chargeId);
    }

    private static java.time.Instant at(String instant) {
        return java.time.Instant.parse(instant);
    }

    // ---- A. Resumen de cartera ------------------------------------------------------------------

    @Test
    void carteraVaciaDevuelveCerosSinInventarImportes() {
        var summary = reportService.portfolio(owner);

        assertThat(summary.confirmedCharges()).isZero();
        assertThat(summary.billedAmount()).isEqualByComparingTo("0");
        assertThat(summary.appliedAmount()).isEqualByComparingTo("0");
        assertThat(summary.pendingBalance()).isEqualByComparingTo("0");
        assertThat(summary.statusCounts().pendiente()).isZero();
        assertThat(summary.statusCounts().parcial()).isZero();
        assertThat(summary.statusCounts().pagado()).isZero();
        assertThat(summary.statusCounts().anulado()).isZero();
    }

    @Test
    void carteraClasificaPendienteParcialPagadoYAnuladoYExcluyeLoAnuladoDelFacturado() {
        confirmed("100.00");                         // PENDIENTE
        Charge parcial = confirmed("100.00");
        pay(parcial, PaymentMethod.CASH, "40.00");    // PARCIAL
        Charge pagado = confirmed("100.00");
        pay(pagado, PaymentMethod.CARD, "100.00");    // PAGADO
        Charge anulado = confirmed("100.00");
        operations.cancelCharge(new CancelChargeCommand(anulado.getId(), "Anulado en prueba", key()), owner);

        var summary = reportService.portfolio(owner);

        assertThat(summary.confirmedCharges()).isEqualTo(4);
        assertThat(summary.billedAmount()).isEqualByComparingTo("300.00");
        assertThat(summary.appliedAmount()).isEqualByComparingTo("140.00");
        assertThat(summary.pendingBalance()).isEqualByComparingTo("160.00");
        assertThat(summary.statusCounts()).satisfies(counts -> {
            assertThat(counts.pendiente()).isEqualTo(1);
            assertThat(counts.parcial()).isEqualTo(1);
            assertThat(counts.pagado()).isEqualTo(1);
            assertThat(counts.anulado()).isEqualTo(1);
        });
    }

    @Test
    void unaReversaDescuentaElPagoOriginalYDevuelveElCargoAPendiente() {
        Charge charge = confirmed("100.10");
        FinancialMovement payment = pay(charge, PaymentMethod.CASH, "40.06");
        reverse(charge, payment);

        var summary = reportService.portfolio(owner);

        assertThat(summary.billedAmount()).isEqualByComparingTo("100.10");
        assertThat(summary.appliedAmount()).isEqualByComparingTo("0");
        assertThat(summary.pendingBalance()).isEqualByComparingTo("100.10");
        assertThat(summary.statusCounts().pendiente()).isEqualTo(1);
        assertThat(summary.statusCounts().parcial()).isZero();
    }

    // ---- B. Cobros por período ------------------------------------------------------------------

    @Test
    void losCobrosSeparanPeriodosPorFechaDeMovimientoYAtribuyenLaReversaASuPagoOriginal() {
        Charge charge = confirmed("100.00");
        FinancialMovement cashP1 = pay(charge, PaymentMethod.CASH, "10.00");
        FinancialMovement cardP1 = pay(charge, PaymentMethod.CARD, "20.00");
        FinancialMovement transferP2 = pay(charge, PaymentMethod.TRANSFER, "5.00");
        FinancialMovement reversalP3 = reverse(charge, cashP1);
        performedAt(cashP1.getId(), P1_FROM);
        performedAt(cardP1.getId(), P1_FROM);
        performedAt(transferP2.getId(), P2_FROM);
        performedAt(reversalP3.getId(), P3_FROM);

        var p1 = reportService.cashFlow(at(P1_FROM), at(P2_FROM), owner);
        assertThat(p1.grossAmount()).isEqualByComparingTo("30.00");
        assertThat(p1.reversalAmount()).isEqualByComparingTo("0");
        assertThat(p1.netAmount()).isEqualByComparingTo("30.00");
        assertThat(method(p1, PaymentMethod.CASH).grossAmount()).isEqualByComparingTo("10.00");
        assertThat(method(p1, PaymentMethod.CARD).grossAmount()).isEqualByComparingTo("20.00");

        var p2 = reportService.cashFlow(at(P2_FROM), at(P3_FROM), owner);
        assertThat(p2.grossAmount()).isEqualByComparingTo("5.00");
        assertThat(p2.reversalAmount()).isEqualByComparingTo("0");
        assertThat(p2.netAmount()).isEqualByComparingTo("5.00");
        assertThat(method(p2, PaymentMethod.TRANSFER).netAmount()).isEqualByComparingTo("5.00");

        var p3 = reportService.cashFlow(at(P3_FROM), null, owner);
        assertThat(p3.grossAmount()).isEqualByComparingTo("0");
        assertThat(p3.reversalAmount()).isEqualByComparingTo("10.00");
        assertThat(p3.netAmount()).isEqualByComparingTo("-10.00");
        assertThat(method(p3, PaymentMethod.CASH).reversalAmount()).isEqualByComparingTo("10.00");
        assertThat(method(p3, PaymentMethod.CASH).netAmount()).isEqualByComparingTo("-10.00");

        var todo = reportService.cashFlow(at(P1_FROM), null, owner);
        assertThat(todo.grossAmount()).isEqualByComparingTo("35.00");
        assertThat(todo.reversalAmount()).isEqualByComparingTo("10.00");
        assertThat(todo.netAmount()).isEqualByComparingTo("25.00");
    }

    @Test
    void unPagoReversadoEnOtroPeriodoNoReduceElNetoDelPeriodoDelPago() {
        Charge charge = confirmed("50.00");
        FinancialMovement payment = pay(charge, PaymentMethod.CASH, "50.00");
        FinancialMovement reversal = reverse(charge, payment);
        performedAt(payment.getId(), P1_FROM);
        performedAt(reversal.getId(), P3_FROM);

        assertThat(reportService.cashFlow(at(P1_FROM), at(P2_FROM), owner).netAmount())
                .isEqualByComparingTo("50.00");
    }

    // ---- C. Cargos y saldos ---------------------------------------------------------------------

    @Test
    void losCargosRespetanFiltrosYDesglosanPorPacienteConSaldoVigente() {
        Charge own = confirmed("100.00");
        pay(own, PaymentMethod.CASH, "30.00");
        confirmedAt(own.getId(), P1_FROM);
        Charge ownTwo = operations.confirmCharge(
                new com.dentalclinic.financial.application.command.ConfirmChargeCommand(
                        otherPatientId, null,
                        List.of(item(null, "Concepto otro", 1, "50.00")), "Nota", key()), owner);
        confirmedAt(ownTwo.getId(), P2_FROM);

        var all = reportService.charges(null, null, null, null, owner);
        assertThat(all.confirmedCharges()).isEqualTo(2);
        assertThat(all.billedAmount()).isEqualByComparingTo("150.00");
        assertThat(all.appliedAmount()).isEqualByComparingTo("30.00");
        assertThat(all.pendingBalance()).isEqualByComparingTo("120.00");
        assertThat(all.byPatient()).hasSize(2);
        assertThat(all.byPatient()).extracting(FinancialReportService.PatientBalance::patientId)
                .containsExactlyInAnyOrder(patientId, otherPatientId);

        var byPatient = reportService.charges(patientId, null, null, null, owner);
        assertThat(byPatient.confirmedCharges()).isEqualTo(1);
        assertThat(byPatient.byPatient()).singleElement()
                .satisfies(patient -> assertThat(patient.pendingBalance()).isEqualByComparingTo("70.00"));

        var byStatus = reportService.charges(null, ChargeStatus.PARCIAL, null, null, owner);
        assertThat(byStatus.confirmedCharges()).isEqualTo(1);
        assertThat(byStatus.billedAmount()).isEqualByComparingTo("100.00");

        var byPeriod = reportService.charges(null, null, at(P2_FROM), null, owner);
        assertThat(byPeriod.confirmedCharges()).isEqualTo(1);
        assertThat(byPeriod.billedAmount()).isEqualByComparingTo("50.00");
    }

    // ---- Autorización y contrato HTTP -----------------------------------------------------------

    @Test
    void soloOwnerYRecepcionAccedenYElRestoRecibe403YElAnonimo401() throws Exception {
        for (String path : List.of("/api/financial/reports/portfolio",
                "/api/financial/reports/cash-flow", "/api/financial/reports/charges")) {
            mvc.perform(get(path).header("Authorization", "Bearer " + token(owner)))
                    .andExpect(status().isOk());
            mvc.perform(get(path).header("Authorization", "Bearer " + token(reception)))
                    .andExpect(status().isOk());
            mvc.perform(get(path).header("Authorization", "Bearer " + token(dentist)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
            mvc.perform(get(path).header("Authorization", "Bearer " + token(patientActor)))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void elContratoHttpExponeLosMontosYRechazaFiltrosInvalidos() throws Exception {
        Charge charge = confirmed("100.10");
        pay(charge, PaymentMethod.CASH, "40.06");

        mvc.perform(get("/api/financial/reports/portfolio")
                .header("Authorization", "Bearer " + token(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billedAmount").value(100.10))
                .andExpect(jsonPath("$.pendingBalance").value(60.04))
                .andExpect(jsonPath("$.statusCounts.parcial").value(1));

        mvc.perform(get("/api/financial/reports/charges")
                .param("status", "PARCIAL").header("Authorization", "Bearer " + token(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmedCharges").value(1));

        mvc.perform(get("/api/financial/reports/cash-flow")
                .param("from", P3_FROM).param("to", P3_FROM)
                .header("Authorization", "Bearer " + token(owner)))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/financial/reports/charges")
                .param("status", "NO_EXISTE").header("Authorization", "Bearer " + token(owner)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void elDentistaNoVeReportesGlobalesDeCarteraAunqueConozcaLaRuta() throws Exception {
        confirmed("100.00");
        mvc.perform(get("/api/financial/reports/portfolio")
                .header("Authorization", "Bearer " + token(dentist)))
                .andExpect(status().isForbidden());
    }

    private static FinancialReportService.MethodFlow method(FinancialReportService.CashFlow cashFlow,
            PaymentMethod method) {
        return cashFlow.methods().stream().filter(flow -> flow.method() == method).findFirst().orElseThrow();
    }
}

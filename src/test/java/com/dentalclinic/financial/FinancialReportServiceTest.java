package com.dentalclinic.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dentalclinic.financial.application.FinancialAuthorizationService;
import com.dentalclinic.financial.application.FinancialReportService;
import com.dentalclinic.financial.domain.ChargeStatus;
import com.dentalclinic.financial.domain.InvalidChargeException;
import com.dentalclinic.financial.domain.PaymentMethod;
import com.dentalclinic.financial.infrastructure.repository.FinancialReportRepository;
import com.dentalclinic.financial.infrastructure.repository.FinancialReportRepository.ChargeGroupTotals;
import com.dentalclinic.financial.infrastructure.repository.FinancialReportRepository.MethodAmount;
import com.dentalclinic.financial.infrastructure.repository.FinancialReportRepository.PatientGroupTotals;
import com.dentalclinic.financial.infrastructure.repository.FinancialReportRepository.PortfolioTotals;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/** Logica de combinacion y validacion del servicio de reportes, sin tocar PostgreSQL. */
class FinancialReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private final AuthenticatedUser owner = new AuthenticatedUser(1L, Role.OWNER);
    private FinancialReportRepository reports;
    private FinancialAuthorizationService authorization;
    private FinancialReportService service;

    @BeforeEach
    void setUp() {
        reports = mock(FinancialReportRepository.class);
        authorization = mock(FinancialAuthorizationService.class);
        service = new FinancialReportService(reports, authorization, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PortfolioTotals portfolio(long total, String billed, String applied,
            long pendiente, long parcial, long pagado, long anulado) {
        PortfolioTotals totals = mock(PortfolioTotals.class);
        when(totals.getTotalCharges()).thenReturn(total);
        when(totals.getBilledAmount()).thenReturn(billed == null ? null : new BigDecimal(billed));
        when(totals.getAppliedAmount()).thenReturn(applied == null ? null : new BigDecimal(applied));
        when(totals.getPendienteCount()).thenReturn(pendiente);
        when(totals.getParcialCount()).thenReturn(parcial);
        when(totals.getPagadoCount()).thenReturn(pagado);
        when(totals.getAnuladoCount()).thenReturn(anulado);
        return totals;
    }

    private static MethodAmount method(String method, String amount) {
        MethodAmount row = mock(MethodAmount.class);
        when(row.getMethod()).thenReturn(method);
        when(row.getAmount()).thenReturn(amount == null ? null : new BigDecimal(amount));
        return row;
    }

    @Test
    void sinCargosLaCarteraEsCeroYNoInventaImportes() {
        PortfolioTotals totals = portfolio(0, null, null, 0, 0, 0, 0);
        when(reports.portfolioTotals()).thenReturn(totals);

        var summary = service.portfolio(owner);

        assertThat(summary.confirmedCharges()).isZero();
        assertThat(summary.billedAmount()).isEqualByComparingTo("0");
        assertThat(summary.appliedAmount()).isEqualByComparingTo("0");
        assertThat(summary.pendingBalance()).isEqualByComparingTo("0");
        assertThat(summary.statusCounts().pendiente()).isZero();
        assertThat(summary.asOf()).isEqualTo(NOW);
        verify(authorization).requireReports(owner);
    }

    @Test
    void laCarteraDerivaElSaldoPendienteDelFacturadoMenosAplicado() {
        PortfolioTotals totals = portfolio(3, "100.10", "40.06", 1, 1, 1, 0);
        when(reports.portfolioTotals()).thenReturn(totals);

        var summary = service.portfolio(owner);

        assertThat(summary.billedAmount()).isEqualByComparingTo("100.10");
        assertThat(summary.appliedAmount()).isEqualByComparingTo("40.06");
        assertThat(summary.pendingBalance()).isEqualByComparingTo("60.04");
    }

    @Test
    void cobrosSinMovimientosDevuelvenLosCuatroMetodosEnCero() {
        when(reports.grossPaymentsByMethod(any(), any())).thenReturn(List.of());
        when(reports.reversalsByMethod(any(), any())).thenReturn(List.of());

        var cashFlow = service.cashFlow(null, null, owner);

        assertThat(cashFlow.grossAmount()).isEqualByComparingTo("0");
        assertThat(cashFlow.reversalAmount()).isEqualByComparingTo("0");
        assertThat(cashFlow.netAmount()).isEqualByComparingTo("0");
        assertThat(cashFlow.methods()).extracting(FinancialReportService.MethodFlow::method)
                .containsExactly(PaymentMethod.CASH, PaymentMethod.CARD, PaymentMethod.TRANSFER, PaymentMethod.OTHER);
        assertThat(cashFlow.methods()).allSatisfy(flow -> {
            assertThat(flow.grossAmount()).isEqualByComparingTo("0");
            assertThat(flow.netAmount()).isEqualByComparingTo("0");
        });
    }

    @Test
    void elCobroNetoRestaReversasYLasAtribuyeASuMetodoOriginal() {
        List<MethodAmount> gross = List.of(method("CASH", "10.00"), method("CARD", "20.00"),
                method("TRANSFER", "5.00"));
        List<MethodAmount> reversals = List.of(method("CASH", "10.00"));
        when(reports.grossPaymentsByMethod(any(), any())).thenReturn(gross);
        when(reports.reversalsByMethod(any(), any())).thenReturn(reversals);

        var cashFlow = service.cashFlow(null, null, owner);

        assertThat(cashFlow.grossAmount()).isEqualByComparingTo("35.00");
        assertThat(cashFlow.reversalAmount()).isEqualByComparingTo("10.00");
        assertThat(cashFlow.netAmount()).isEqualByComparingTo("25.00");
        assertThat(flow(cashFlow, PaymentMethod.CASH)).satisfies(flow -> {
            assertThat(flow.grossAmount()).isEqualByComparingTo("10.00");
            assertThat(flow.reversalAmount()).isEqualByComparingTo("10.00");
            assertThat(flow.netAmount()).isEqualByComparingTo("0");
        });
        assertThat(flow(cashFlow, PaymentMethod.CARD).netAmount()).isEqualByComparingTo("20.00");
        assertThat(flow(cashFlow, PaymentMethod.TRANSFER).netAmount()).isEqualByComparingTo("5.00");
        assertThat(flow(cashFlow, PaymentMethod.OTHER).netAmount()).isEqualByComparingTo("0");
    }

    @Test
    void ignoraRecuentosDeMetodoNuloEnLugarDeRomper() {
        List<MethodAmount> gross = List.of(method(null, "10.00"), method("CARD", "3.00"));
        when(reports.grossPaymentsByMethod(any(), any())).thenReturn(gross);
        when(reports.reversalsByMethod(any(), any())).thenReturn(List.of());

        var cashFlow = service.cashFlow(null, null, owner);

        assertThat(cashFlow.grossAmount()).isEqualByComparingTo("3.00");
    }

    @Test
    void rechazaUnIntervaloInvertidoODeLongitudCero() {
        Instant start = Instant.parse("2026-10-15T00:00:00Z");
        assertThatThrownBy(() -> service.cashFlow(start, start, owner)).isInstanceOf(InvalidChargeException.class);
        assertThatThrownBy(() -> service.charges(null, null, start, start.minusSeconds(1), owner))
                .isInstanceOf(InvalidChargeException.class);
        verifyNoInteractions(reports);
    }

    @Test
    void elDesglosePorPacienteDerivaElSaldoPendienteYRespetaElFiltro() {
        ChargeGroupTotals totals = mock(ChargeGroupTotals.class);
        when(totals.getChargeCount()).thenReturn(3L);
        when(totals.getBilledAmount()).thenReturn(new BigDecimal("300.00"));
        when(totals.getAppliedAmount()).thenReturn(new BigDecimal("150.00"));
        when(reports.chargesTotals(eq(patientId()), eq("PARCIAL"), any(), any())).thenReturn(totals);
        PatientGroupTotals row = mock(PatientGroupTotals.class);
        when(row.getPatientId()).thenReturn(patientId());
        when(row.getChargeCount()).thenReturn(3L);
        when(row.getBilledAmount()).thenReturn(new BigDecimal("300.00"));
        when(row.getAppliedAmount()).thenReturn(new BigDecimal("150.00"));
        when(reports.chargesByPatient(eq(patientId()), eq("PARCIAL"), any(), any())).thenReturn(List.of(row));

        var balance = service.charges(patientId(), ChargeStatus.PARCIAL, null, null, owner);

        assertThat(balance.pendingBalance()).isEqualByComparingTo("150.00");
        assertThat(balance.byPatient()).singleElement().satisfies(patient -> {
            assertThat(patient.patientId()).isEqualTo(patientId());
            assertThat(patient.pendingBalance()).isEqualByComparingTo("150.00");
        });
    }

    private static FinancialReportService.MethodFlow flow(FinancialReportService.CashFlow cashFlow, PaymentMethod method) {
        return cashFlow.methods().stream().filter(flow -> flow.method() == method).findFirst().orElseThrow();
    }

    private static Long patientId() {
        return 42L;
    }
}

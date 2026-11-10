package com.dentalclinic.financial.api;

import java.time.Instant;

import jakarta.validation.constraints.Positive;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dentalclinic.financial.api.dto.CashFlowReportResponse;
import com.dentalclinic.financial.api.dto.ChargesBalanceReportResponse;
import com.dentalclinic.financial.api.dto.PortfolioReportResponse;
import com.dentalclinic.financial.application.FinancialReportService;
import com.dentalclinic.financial.domain.ChargeStatus;
import com.dentalclinic.identity.infrastructure.security.CurrentUser;

/**
 * Transporte HTTP de los reportes financieros administrativos (Fase 4C).
 *
 * <p>Solo lectura y solo globles: la autorización (OWNER/RECEPTIONIST) se aplica aquí en la capa de
 * aplicación y en la matriz HTTP ({@code EndpointGroup.FINANCIAL_REPORTS}); DENTIST y PATIENT
 * reciben 403. Toda la agregación ocurre en PostgreSQL: el transporte no suma nada.
 *
 * <p>Los parámetros {@code from}/{@code to} son instantes ISO-8601 con inicio inclusivo y fin
 * exclusivo, idénticos a {@code GET /api/charges}.
 */
@Validated
@RestController
@RequestMapping("/api/financial/reports")
public class FinancialReportController {

    private final FinancialReportService reports;

    public FinancialReportController(FinancialReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/portfolio")
    public PortfolioReportResponse portfolio() {
        return PortfolioReportResponse.from(reports.portfolio(CurrentUser.require()));
    }

    @GetMapping("/cash-flow")
    public CashFlowReportResponse cashFlow(@RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return CashFlowReportResponse.from(from, to, reports.cashFlow(from, to, CurrentUser.require()));
    }

    @GetMapping("/charges")
    public ChargesBalanceReportResponse charges(@RequestParam(required = false) @Positive Long patientId,
            @RequestParam(required = false) ChargeStatus status,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        return ChargesBalanceReportResponse.from(patientId, status, from, to,
                reports.charges(patientId, status, from, to, CurrentUser.require()));
    }
}

package com.dentalclinic.financial.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.dentalclinic.financial.application.FinancialReportService.PortfolioSummary;

/**
 * A. Resumen de cartera en <strong>estado actual</strong> (no un corte histórico).
 *
 * @param asOf             instante del servidor al calcular el resumen
 * @param confirmedCharges total de cargos confirmados (incluye anulados)
 * @param billedAmount     facturación administrativa vigente: cargos no anulados
 * @param appliedAmount    abonos válidos vigentes (pagos no revertidos)
 * @param pendingBalance   saldo pendiente vigente ({@code billedAmount - appliedAmount})
 * @param statusCounts     conteo por estado derivado; suman {@code confirmedCharges}
 */
public record PortfolioReportResponse(Instant asOf, long confirmedCharges, BigDecimal billedAmount,
        BigDecimal appliedAmount, BigDecimal pendingBalance, StatusCounts statusCounts) {

    public record StatusCounts(long pendiente, long parcial, long pagado, long anulado) { }

    public static PortfolioReportResponse from(PortfolioSummary summary) {
        var counts = summary.statusCounts();
        return new PortfolioReportResponse(summary.asOf(), summary.confirmedCharges(), summary.billedAmount(),
                summary.appliedAmount(), summary.pendingBalance(),
                new StatusCounts(counts.pendiente(), counts.parcial(), counts.pagado(), counts.anulado()));
    }
}

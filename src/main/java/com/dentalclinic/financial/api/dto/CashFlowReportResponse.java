package com.dentalclinic.financial.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.dentalclinic.financial.application.FinancialReportService.CashFlow;
import com.dentalclinic.financial.domain.PaymentMethod;

/**
 * B. Cobros por período (por fecha de movimiento, con inicio inclusivo y fin exclusivo).
 *
 * <p>Separa pagos brutos y reversas registrados DURANTE el período; no es un corte de cargos
 * confirmados en el período. Las reversas se atribuyen al método del pago original.
 *
 * @param from           límite inferior del período (inclusive); {@code null} si no se filtró
 * @param to             límite superior del período (exclusivo); {@code null} si no se filtró
 * @param grossAmount    suma de PAYMENT registrados en el período
 * @param reversalAmount suma de REVERSAL registradas en el período
 * @param netAmount      cobro neto ({@code grossAmount - reversalAmount})
 * @param methods        desglose por método (CASH, CARD, TRANSFER, OTHER), siempre los cuatro
 */
public record CashFlowReportResponse(Instant from, Instant to, BigDecimal grossAmount,
        BigDecimal reversalAmount, BigDecimal netAmount, List<MethodFlow> methods) {

    public record MethodFlow(PaymentMethod method, BigDecimal grossAmount, BigDecimal reversalAmount,
                             BigDecimal netAmount) { }

    public static CashFlowReportResponse from(Instant from, Instant to, CashFlow cashFlow) {
        List<MethodFlow> methods = cashFlow.methods().stream()
                .map(flow -> new MethodFlow(flow.method(), flow.grossAmount(), flow.reversalAmount(),
                        flow.netAmount()))
                .toList();
        return new CashFlowReportResponse(from, to, cashFlow.grossAmount(), cashFlow.reversalAmount(),
                cashFlow.netAmount(), methods);
    }
}

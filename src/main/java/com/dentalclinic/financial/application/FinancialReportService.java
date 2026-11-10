package com.dentalclinic.financial.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.financial.domain.ChargeStatus;
import com.dentalclinic.financial.domain.InvalidChargeException;
import com.dentalclinic.financial.domain.PaymentMethod;
import com.dentalclinic.financial.infrastructure.repository.FinancialReportRepository;
import com.dentalclinic.financial.infrastructure.repository.FinancialReportRepository.MethodAmount;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/**
 * Reportes financieros administrativos de solo lectura (Fase 4C).
 *
 * <p>Autoriza por rol (solo {@code OWNER}/{@code RECEPTIONIST}) antes de consultar y delega toda la
 * agregación en PostgreSQL ({@link FinancialReportRepository}); el servicio no persiste saldos ni
 * estados y nunca carga movimientos a memoria.
 *
 * <p><strong>Semántica de fechas:</strong> inicio inclusivo y fin exclusivo
 * ({@code >= from AND < to}). Cartera y cargos se cortan por {@code charge.confirmed_at}; los cobros
 * por {@code financial_movement.performed_at}. Separar ambas nociones evita confundir cobros del
 * período con cargos confirmados durante el período.
 *
 * <p><strong>Reversas:</strong> una {@code REVERSAL} descuenta el pago original del saldo vigente
 * (misma semántica de abono válido que listados/detalle); en el reporte de cobros por período, los
 * pagos brutos y las reversas se agrupan por separado y las reversas se atribuyen al método del pago
 * original. El cobro neto es {@code bruto - reversas}.
 *
 * <p>La unidad de cartera (A) y de cargos (C) es el <strong>estado actual</strong>, no un corte
 * histórico: los saldos reflejan los pagos válidos vigentes a la fecha de consulta.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class FinancialReportService {

    private final FinancialReportRepository reports;
    private final FinancialAuthorizationService authorization;
    private final Clock clock;

    public FinancialReportService(FinancialReportRepository reports,
                                  FinancialAuthorizationService authorization, Clock clock) {
        this.reports = reports;
        this.authorization = authorization;
        this.clock = clock;
    }

    /** A. Resumen de cartera (estado actual). */
    public PortfolioSummary portfolio(AuthenticatedUser actor) {
        authorization.requireReports(actor);
        var totals = reports.portfolioTotals();
        BigDecimal billed = zeroIfNull(totals.getBilledAmount());
        BigDecimal applied = zeroIfNull(totals.getAppliedAmount());
        return new PortfolioSummary(totals.getTotalCharges(), billed, applied, billed.subtract(applied),
                new StatusCounts(totals.getPendienteCount(), totals.getParcialCount(),
                        totals.getPagadoCount(), totals.getAnuladoCount()),
                clock.instant());
    }

    /** B. Cobros por período (por fecha de movimiento). */
    public CashFlow cashFlow(Instant from, Instant to, AuthenticatedUser actor) {
        authorization.requireReports(actor);
        requireOrderedRange(from, to);
        Map<PaymentMethod, BigDecimal> gross = aggregate(reports.grossPaymentsByMethod(from, to));
        Map<PaymentMethod, BigDecimal> reversals = aggregate(reports.reversalsByMethod(from, to));
        List<MethodFlow> methods = new ArrayList<>();
        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalReversals = BigDecimal.ZERO;
        for (PaymentMethod method : PaymentMethod.values()) {
            BigDecimal g = zeroIfNull(gross.get(method));
            BigDecimal r = zeroIfNull(reversals.get(method));
            totalGross = totalGross.add(g);
            totalReversals = totalReversals.add(r);
            methods.add(new MethodFlow(method, g, r, g.subtract(r)));
        }
        return new CashFlow(totalGross, totalReversals, totalGross.subtract(totalReversals), methods);
    }

    /** C. Cargos y saldos por paciente con filtros administrativos. */
    public ChargesBalance charges(Long patientId, ChargeStatus status, Instant from, Instant to,
                                  AuthenticatedUser actor) {
        authorization.requireReports(actor);
        requireOrderedRange(from, to);
        String statusName = status == null ? null : status.name();
        var totals = reports.chargesTotals(patientId, statusName, from, to);
        BigDecimal billed = zeroIfNull(totals.getBilledAmount());
        BigDecimal applied = zeroIfNull(totals.getAppliedAmount());
        List<PatientBalance> byPatient = reports.chargesByPatient(patientId, statusName, from, to).stream()
                .map(row -> {
                    BigDecimal patientBilled = zeroIfNull(row.getBilledAmount());
                    BigDecimal patientApplied = zeroIfNull(row.getAppliedAmount());
                    return new PatientBalance(row.getPatientId(), row.getChargeCount(), patientBilled,
                            patientApplied, patientBilled.subtract(patientApplied));
                }).toList();
        return new ChargesBalance(totals.getChargeCount(), billed, applied, billed.subtract(applied), byPatient);
    }

    private static Map<PaymentMethod, BigDecimal> aggregate(List<MethodAmount> rows) {
        Map<PaymentMethod, BigDecimal> result = new EnumMap<>(PaymentMethod.class);
        for (MethodAmount row : rows) {
            if (row.getMethod() == null) {
                continue; // Un PAYMENT sin método no es posible por la forma del esquema.
            }
            result.merge(PaymentMethod.valueOf(row.getMethod()), zeroIfNull(row.getAmount()), BigDecimal::add);
        }
        return result;
    }

    private static void requireOrderedRange(Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new InvalidChargeException("El inicio del intervalo debe ser anterior al fin.");
        }
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record PortfolioSummary(long confirmedCharges, BigDecimal billedAmount, BigDecimal appliedAmount,
                                   BigDecimal pendingBalance, StatusCounts statusCounts, Instant asOf) { }

    public record StatusCounts(long pendiente, long parcial, long pagado, long anulado) { }

    public record CashFlow(BigDecimal grossAmount, BigDecimal reversalAmount, BigDecimal netAmount,
                           List<MethodFlow> methods) { }

    public record MethodFlow(PaymentMethod method, BigDecimal grossAmount, BigDecimal reversalAmount,
                             BigDecimal netAmount) { }

    public record ChargesBalance(long confirmedCharges, BigDecimal billedAmount, BigDecimal appliedAmount,
                                 BigDecimal pendingBalance, List<PatientBalance> byPatient) { }

    public record PatientBalance(Long patientId, long chargeCount, BigDecimal billedAmount,
                                 BigDecimal appliedAmount, BigDecimal pendingBalance) { }
}

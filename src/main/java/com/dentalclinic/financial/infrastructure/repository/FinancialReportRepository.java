package com.dentalclinic.financial.infrastructure.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.dentalclinic.financial.domain.Charge;

/**
 * Consultas de agregación de los reportes financieros administrativos (Fase 4C).
 *
 * <p>Todas las sumas se calculan <strong>en PostgreSQL</strong>: nunca se cargan movimientos a
 * memoria ni se suman páginas del frontend. Reutiliza {@link ChargeRepository#APPLIED_JOIN} y
 * {@link ChargeRepository#STATUS_CASE} para compartir la misma semántica de "abono válido"
 * (pagos no revertidos) y de estado derivado que usan los listados y el detalle.
 *
 * <p><strong>Semántica de fechas:</strong> inicio inclusivo y fin exclusivo
 * ({@code >= :from AND < :to}), idéntica a {@code ChargeRepository#findVisible}. En cartera y
 * cargos el corte es {@code charge.confirmed_at}; en cobros es
 * {@code financial_movement.performed_at}.
 *
 * <p>No persiste saldos ni estados: son proyecciones efímeras de solo lectura.
 */
public interface FinancialReportRepository extends Repository<Charge, Long> {

    /**
     * Filtro administrativo por paciente, estado y fecha de confirmación. No aplica alcance por
     * dentista: los reportes son globales y solo los ven OWNER/RECEPTIONIST (autorizado en la capa
     * de aplicación y reflejado en {@code EndpointGroup}).
     */
    String CHARGE_FILTER = """
            WHERE (CAST(:patientId AS BIGINT) IS NULL OR c.patient_id = CAST(:patientId AS BIGINT))
              AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR c.confirmed_at >= CAST(:from AS TIMESTAMPTZ))
              AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR c.confirmed_at < CAST(:to AS TIMESTAMPTZ))
              AND (CAST(:status AS VARCHAR) IS NULL OR CAST(:status AS VARCHAR) = \
            """ + ChargeRepository.STATUS_CASE + ")";

    /** Importe vigente de cargos no anulados; los anulados quedan sin efecto (no facturan). */
    String BILLED_EXPRESSION = "CASE WHEN c.cancelled_at IS NULL THEN c.total_amount ELSE 0 END";

    /** Abono válido vigente de cargos no anulados (los anulados no conservan pagos válidos). */
    String APPLIED_EXPRESSION = "CASE WHEN c.cancelled_at IS NULL THEN paid.applied ELSE 0 END";

    String FROM_CHARGE = "FROM charge c " + ChargeRepository.APPLIED_JOIN;

    /** Resumen de cartera (estado actual, sin corte histórico). */
    interface PortfolioTotals {
        long getTotalCharges();
        BigDecimal getBilledAmount();
        BigDecimal getAppliedAmount();
        long getPendienteCount();
        long getParcialCount();
        long getPagadoCount();
        long getAnuladoCount();
    }

    /** Importe agregado por método de pago. */
    interface MethodAmount {
        String getMethod();
        BigDecimal getAmount();
    }

    interface ChargeGroupTotals {
        long getChargeCount();
        BigDecimal getBilledAmount();
        BigDecimal getAppliedAmount();
    }

    interface PatientGroupTotals {
        Long getPatientId();
        long getChargeCount();
        BigDecimal getBilledAmount();
        BigDecimal getAppliedAmount();
    }

    @Query(nativeQuery = true, value =
            "SELECT COUNT(*) AS \"totalCharges\", "
            + "COALESCE(SUM(" + BILLED_EXPRESSION + "), 0) AS \"billedAmount\", "
            + "COALESCE(SUM(" + APPLIED_EXPRESSION + "), 0) AS \"appliedAmount\", "
            + "COUNT(*) FILTER (WHERE c.cancelled_at IS NULL AND paid.applied = 0) AS \"pendienteCount\", "
            + "COUNT(*) FILTER (WHERE c.cancelled_at IS NULL AND paid.applied > 0"
            + " AND paid.applied < c.total_amount) AS \"parcialCount\", "
            + "COUNT(*) FILTER (WHERE c.cancelled_at IS NULL"
            + " AND paid.applied = c.total_amount) AS \"pagadoCount\", "
            + "COUNT(*) FILTER (WHERE c.cancelled_at IS NOT NULL) AS \"anuladoCount\" "
            + FROM_CHARGE)
    PortfolioTotals portfolioTotals();

    /** Pagos brutos registrados en el período, agrupados por el método del propio pago. */
    @Query(nativeQuery = true, value = """
            SELECT m.payment_method AS "method", SUM(m.amount) AS "amount"
            FROM financial_movement m
            WHERE m.type = 'PAYMENT'
              AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR m.performed_at >= CAST(:from AS TIMESTAMPTZ))
              AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR m.performed_at < CAST(:to AS TIMESTAMPTZ))
            GROUP BY m.payment_method
            """)
    List<MethodAmount> grossPaymentsByMethod(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * Reversas registradas en el período, atribuidas al método del PAYMENT original. La reversa no
     * guarda método propio: se une con el movimiento que compensa ({@code reverses_movement_id}).
     */
    @Query(nativeQuery = true, value = """
            SELECT p.payment_method AS "method", SUM(r.amount) AS "amount"
            FROM financial_movement r
            JOIN financial_movement p ON p.id = r.reverses_movement_id
            WHERE r.type = 'REVERSAL'
              AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR r.performed_at >= CAST(:from AS TIMESTAMPTZ))
              AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR r.performed_at < CAST(:to AS TIMESTAMPTZ))
            GROUP BY p.payment_method
            """)
    List<MethodAmount> reversalsByMethod(@Param("from") Instant from, @Param("to") Instant to);

    @Query(nativeQuery = true, value =
            "SELECT COUNT(*) AS \"chargeCount\", "
            + "COALESCE(SUM(" + BILLED_EXPRESSION + "), 0) AS \"billedAmount\", "
            + "COALESCE(SUM(" + APPLIED_EXPRESSION + "), 0) AS \"appliedAmount\" "
            + FROM_CHARGE + CHARGE_FILTER)
    ChargeGroupTotals chargesTotals(@Param("patientId") Long patientId, @Param("status") String status,
            @Param("from") Instant from, @Param("to") Instant to);

    @Query(nativeQuery = true, value =
            "SELECT c.patient_id AS \"patientId\", COUNT(*) AS \"chargeCount\", "
            + "COALESCE(SUM(" + BILLED_EXPRESSION + "), 0) AS \"billedAmount\", "
            + "COALESCE(SUM(" + APPLIED_EXPRESSION + "), 0) AS \"appliedAmount\" "
            + FROM_CHARGE + CHARGE_FILTER
            + " GROUP BY c.patient_id"
            + " ORDER BY SUM(" + BILLED_EXPRESSION + " - " + APPLIED_EXPRESSION + ") DESC, c.patient_id ASC")
    List<PatientGroupTotals> chargesByPatient(@Param("patientId") Long patientId, @Param("status") String status,
            @Param("from") Instant from, @Param("to") Instant to);
}

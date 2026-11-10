package com.dentalclinic.financial.infrastructure.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.dentalclinic.financial.domain.FinancialMovement;

/**
 * Repositorio de movimientos financieros: <strong>append-only</strong>.
 *
 * <p>No extiende {@code JpaRepository}/{@code CrudRepository} a proposito: asi NO se exponen los
 * metodos heredados de mutacion/borrado ({@code delete}, {@code deleteById}, {@code deleteAll},
 * {@code saveAll}, ...). Solo se declara {@code save} (alta) y consultas de lectura. Es el mismo
 * patron que los historiales inmutables del odontograma.
 *
 * <p>La inmutabilidad es de <strong>aplicacion</strong>: la entidad no tiene setters y la base NO
 * usa triggers/RLS. La base solo garantiza {@code ON DELETE RESTRICT} y
 * {@code UNIQUE(reverses_movement_id)}.
 */
public interface FinancialMovementRepository extends Repository<FinancialMovement, Long> {

    /** Alta (append). Un movimiento nunca se actualiza. */
    FinancialMovement save(FinancialMovement movement);

    Optional<FinancialMovement> findById(Long id);

    /**
     * Suma de abonos VALIDOS de un cargo: solo {@code PAYMENT} cuyo {@code id} NO es referencia
     * de ninguna {@code REVERSAL}. Por tanto:
     *
     * <ul>
     *   <li>No cuenta pagos ya revertidos.</li>
     *   <li>No cuenta dos veces un movimiento.</li>
     *   <li>No cuenta la reversa como un nuevo ingreso (es tipo {@code REVERSAL}).</li>
     * </ul>
     *
     * <p>El MVP solo admite reversas COMPLETAS: en 2B se debe cargar el pago original y usar
     * exactamente su importe, cargo y paciente. Esta consulta excluye el pago completo; no
     * implementa compensaciones parciales. El tipo PAYMENT es fijo, no un parametro del cliente.
     *
     * <p>Devuelve {@link Optional#empty()} cuando no hay ningun abono valido; el llamador
     * (Fase 2) debe tratarlo como {@link BigDecimal#ZERO}. NO usa {@code FOR UPDATE} sobre la
     * agregacion.
     */
    @Query("""
            SELECT SUM(m.amount) FROM FinancialMovement m
            WHERE m.chargeId = :chargeId
              AND m.type = com.dentalclinic.financial.domain.FinancialMovementType.PAYMENT
              AND NOT EXISTS (
                  SELECT 1 FROM FinancialMovement r WHERE r.reversesMovementId = m.id
                    AND r.type = com.dentalclinic.financial.domain.FinancialMovementType.REVERSAL
              )
            """)
    Optional<BigDecimal> sumValidAppliedAmount(@Param("chargeId") Long chargeId);

    List<FinancialMovement> findByChargeIdOrderByPerformedAtAscIdAsc(Long chargeId);

    /**
     * Movimientos de <strong>varios</strong> cargos en una sola consulta, agrupados por
     * {@code chargeId} y con orden estable ({@code chargeId}, {@code performedAt}, {@code id}).
     *
     * <p>Existe para el contexto financiero de una atencion (UX-05B.1, Etapa 2), donde el conjunto
     * de cargos se carga completo y sus movimientos se leen en lote. Solo lectura.
     */
    List<FinancialMovement> findByChargeIdInOrderByChargeIdAscPerformedAtAscIdAsc(List<Long> chargeIds);

    /**
     * Reversas que referencian cualquiera de los movimientos indicados, <strong>aunque pertenezcan a
     * otro cargo</strong>.
     *
     * <p>Existe para el contexto financiero de una atencion (UX-05B.1, Etapa 2): una reversa valida
     * debe pertenecer al mismo cargo que el pago que compensa. Cargar solo por {@code chargeId}
     * ocultaria una reversa cruzada y dejaria el saldo del pago como si siguiera vigente. Solo
     * lectura.
     */
    List<FinancialMovement> findByReversesMovementIdInOrderByReversesMovementIdAscIdAsc(
            List<Long> reversesMovementIds);

    boolean existsByReversesMovementId(Long reversesMovementId);

    interface AppliedAmount {
        Long getChargeId();
        BigDecimal getAmount();
    }

    /** Agregacion en lote de la pagina YA autorizada, con la misma semantica de abonos validos. */
    @Query("""
            SELECT m.chargeId AS chargeId, SUM(m.amount) AS amount FROM FinancialMovement m
            WHERE m.chargeId IN :chargeIds
              AND m.type = com.dentalclinic.financial.domain.FinancialMovementType.PAYMENT
              AND NOT EXISTS (SELECT 1 FROM FinancialMovement r WHERE r.reversesMovementId = m.id
                  AND r.type = com.dentalclinic.financial.domain.FinancialMovementType.REVERSAL)
            GROUP BY m.chargeId
            """)
    List<AppliedAmount> appliedAmounts(@Param("chargeIds") List<Long> chargeIds);
}

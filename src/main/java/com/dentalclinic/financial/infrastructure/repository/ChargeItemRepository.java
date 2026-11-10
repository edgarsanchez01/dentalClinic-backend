package com.dentalclinic.financial.infrastructure.repository;

import java.util.List;

import org.springframework.data.repository.Repository;

import com.dentalclinic.financial.domain.ChargeItem;

/**
 * Repositorio de conceptos de cargo: <strong>append-only</strong> tras confirmar el cargo.
 *
 * <p>No extiende {@code JpaRepository}/{@code CrudRepository}: no se exponen {@code delete},
 * {@code deleteById}, {@code deleteAll}, {@code saveAll}, etc. Solo {@code save} (alta) y lecturas.
 * Mismo patron que los historiales inmutables del odontograma.
 *
 * <p>La inmutabilidad es de <strong>aplicacion</strong> (entidad sin setters); la base no usa
 * triggers/RLS y solo garantiza {@code ON DELETE RESTRICT} desde {@code charge}.
 */
public interface ChargeItemRepository extends Repository<ChargeItem, Long> {

    /** Alta (append). Un concepto confirmado no se actualiza. */
    ChargeItem save(ChargeItem item);

    List<ChargeItem> findByChargeIdOrderByIdAsc(Long chargeId);

    /**
     * Conceptos de <strong>varios</strong> cargos en una sola consulta, agrupados por
     * {@code chargeId} y con orden estable ({@code chargeId}, {@code id}).
     *
     * <p>Existe para el contexto financiero de una atencion (UX-05B.1, Etapa 2), donde el conjunto
     * de cargos se carga completo y sus conceptos se leen en lote: consultarlos cargo a cargo
     * multiplicaria las consultas sin cambiar el resultado.
     */
    List<ChargeItem> findByChargeIdInOrderByChargeIdAscIdAsc(List<Long> chargeIds);

    long countByChargeId(Long chargeId);
}

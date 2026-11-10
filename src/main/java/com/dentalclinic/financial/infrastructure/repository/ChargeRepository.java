package com.dentalclinic.financial.infrastructure.repository;

import java.util.Optional;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.dentalclinic.financial.domain.Charge;

import jakarta.persistence.LockModeType;

/**
 * Repositorio de cargos. El cargo SI nace confirmado y solo admite la <strong>anulacion
 * explicita</strong> (cambio de estado controlado por {@code Charge.cancel}), nunca borrado.
 *
 * <p>No extiende {@code JpaRepository}/{@code CrudRepository}: no se exponen {@code delete},
 * {@code deleteById}, {@code deleteAll}, {@code saveAll}, etc. Solo {@code save} (alta/anulacion),
 * lecturas y el bloqueo pesimista. Evita caminos publicos de borrado en el modulo financiero.
 */
public interface ChargeRepository extends Repository<Charge, Long> {

    /** IDs only: entities must first enter the persistence context under their write lock. */
    @Query("select c.id from Charge c where c.appointmentId = :appointmentId order by c.id")
    List<Long> findIdsByAppointmentId(@Param("appointmentId") Long appointmentId);

    /** Alta o actualizacion controlada (anulacion). El borrado no se expone. */
    Charge save(Charge charge);

    Optional<Charge> findById(Long id);

    /**
     * Carga un cargo tomando un bloqueo pesimista de fila ({@code SELECT ... FOR UPDATE} a traves
     * de JPA). Fase 2B lo usa para serializar operaciones financieras concurrentes sobre el
     * MISMO cargo y evitar sobrepagos.
     *
     * <p>Se expresa con {@link Lock} + JPQL (sin concatenar {@code FOR UPDATE} al texto de la
     * consulta) y SOLO bloquea la fila del cargo; nunca una agregacion {@code SUM}.
     *
     * <p>El bloqueo se mantiene durante comprobaciones, escritura y registro idempotente.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Charge c WHERE c.id = :id")
    Optional<Charge> findByIdForUpdate(@Param("id") Long id);

    Page<Charge> findByPatientIdOrderByCreatedAtDescIdDesc(Long patientId, Pageable pageable);

    Page<Charge> findByAppointmentIdOrderByCreatedAtDescIdDesc(Long appointmentId, Pageable pageable);

    /**
     * <strong>Todos</strong> los cargos asociados a una cita, <strong>sin paginacion</strong> y sin
     * filtros que puedan ocultar cargos.
     *
     * <p>Existe para el contexto financiero de una atencion (UX-05B.1, Etapa 2), donde el conjunto
     * debe devolverse <strong>completo o no devolverse</strong>: una vista paginada o filtrada
     * presentaria un subconjunto como si fuera el conjunto real, y ocultaria un cargo anulado o
     * pagado. El orden por {@code id} es estable y reproducible.
     *
     * <p>Solo lectura: no altera el patron append-only del modulo.
     */
    List<Charge> findByAppointmentIdOrderByIdAsc(Long appointmentId);

    /*
     * Una fila por cargo: el mismo WHERE se aplica a SELECT y COUNT, antes de LIMIT/OFFSET.
     * EXISTS/NOT EXISTS reproducen la pertenencia de Fase 2A, incluidos paciente y conceptos.
     * LATERAL calcula abonos validos por cargo usando financial_movement_charge_idx y la UNIQUE
     * de reversas; charge_item_charge_idx sirve las comprobaciones de conceptos. Sin N+1 de
     * autorizacion, sin filtrar paginas en memoria y sin join que multiplique cargos/conteos.
     */
    String APPLIED_JOIN = """
            LEFT JOIN LATERAL (
                SELECT COALESCE(SUM(m.amount), 0) AS applied
                FROM financial_movement m
                WHERE m.charge_id = c.id AND m.type = 'PAYMENT'
                  AND NOT EXISTS (SELECT 1 FROM financial_movement r
                      WHERE r.reverses_movement_id = m.id AND r.type = 'REVERSAL')
            ) paid ON TRUE
            """;

    /**
     * Expresión SQL del estado <strong>derivado</strong> de un cargo. Usa los alias {@code c}
     * (charge) y {@code paid} (LATERAL de abonos válidos definido en {@link #APPLIED_JOIN}); por eso
     * solo es válida junto a esa proyección. Se comparte con
     * {@link FinancialReportRepository} para no duplicar la derivación de estado.
     */
    String STATUS_CASE = "CASE"
            + " WHEN c.cancelled_at IS NOT NULL THEN 'ANULADO'"
            + " WHEN paid.applied = 0 THEN 'PENDIENTE'"
            + " WHEN paid.applied < c.total_amount THEN 'PARCIAL'"
            + " WHEN paid.applied = c.total_amount THEN 'PAGADO'"
            + " ELSE 'INVALID' END";

    String VISIBLE_FILTER = """
            WHERE (CAST(:patientId AS BIGINT) IS NULL OR c.patient_id = CAST(:patientId AS BIGINT))
              AND (CAST(:appointmentId AS BIGINT) IS NULL OR c.appointment_id = CAST(:appointmentId AS BIGINT))
              AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR c.confirmed_at >= CAST(:from AS TIMESTAMPTZ))
              AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR c.confirmed_at < CAST(:to AS TIMESTAMPTZ))
              AND (CAST(:dentistId AS BIGINT) IS NULL OR (
                  EXISTS (SELECT 1 FROM appointment a WHERE a.id = c.appointment_id
                      AND a.patient_id = c.patient_id AND a.dentist_id = CAST(:dentistId AS BIGINT))
                  AND EXISTS (SELECT 1 FROM charge_item i WHERE i.charge_id = c.id)
                  AND NOT EXISTS (SELECT 1 FROM charge_item i WHERE i.charge_id = c.id
                      AND i.treating_dentist_id IS NOT NULL
                      AND i.treating_dentist_id <> CAST(:dentistId AS BIGINT))
              ))
              AND (CAST(:status AS VARCHAR) IS NULL OR CAST(:status AS VARCHAR) = \
            """ + STATUS_CASE + ")";

    @Query(value = "SELECT c.* FROM charge c " + APPLIED_JOIN + VISIBLE_FILTER
            + " ORDER BY c.confirmed_at DESC, c.id DESC",
            countQuery = "SELECT COUNT(*) FROM charge c " + APPLIED_JOIN + VISIBLE_FILTER,
            nativeQuery = true)
    Page<Charge> findVisible(@Param("patientId") Long patientId, @Param("appointmentId") Long appointmentId,
            @Param("dentistId") Long dentistId, @Param("status") String status,
            @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);
}

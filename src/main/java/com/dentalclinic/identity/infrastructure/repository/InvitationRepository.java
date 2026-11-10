package com.dentalclinic.identity.infrastructure.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dentalclinic.identity.domain.Invitation;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {

    Optional<Invitation> findByTokenHash(String tokenHash);

    /**
     * ¿La cuenta tiene una invitación sin usar? Es lo que distingue una cuenta {@code PENDING}
     * (nunca activada; su estado se gestiona con el enlace) de una {@code INACTIVE} (ya estuvo
     * activa; Fase 8A). {@code user_id} es único, así que como mucho hay una fila.
     */
    boolean existsByUserIdAndUsedAtIsNull(Long userId);

    /** Ids de cuentas con invitación sin usar, para clasificar el listado en una sola consulta. */
    @Query("SELECT i.userId FROM Invitation i WHERE i.usedAt IS NULL AND i.userId IS NOT NULL")
    java.util.Set<Long> findPendingUserIds();

    /**
     * Consumo atómico de un token de un solo uso (RF-IA-09, §24.6).
     *
     * <p>Un {@code SELECT} seguido de un {@code UPDATE} dejaría pasar dos activaciones
     * simultáneas del mismo token. Un único {@code UPDATE} condicional sobre la fila garantiza
     * que solo la primera gana; la segunda recibe 0 filas.
     *
     * <p>La condición sobre {@code user_id} impide vincular una invitación a una cuenta distinta
     * de aquella a la que ya estaba enlazada, que es lo que exigirá la unicidad del constraint.
     *
     * @return 1 si este hilo consumió la invitación; 0 si ya se usó, expiró, no existe o apunta a
     *     otra cuenta
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Invitation i
               SET i.usedAt = :now,
                   i.userId = :userId
             WHERE i.tokenHash = :tokenHash
               AND i.usedAt IS NULL
               AND i.expiresAt > :now
               AND (i.userId IS NULL OR i.userId = :userId)
            """)
    int consumeAtomically(@Param("tokenHash") String tokenHash,
                          @Param("userId") Long userId,
                          @Param("now") Instant now);

    /**
     * Elimina las invitaciones pendientes de una cuenta (Fase 3/docs dev).
     *
     * <p>Se usa al reemitir una invitación: {@code user_id} es único, así que antes de emitir un
     * token nuevo hay que retirar el anterior. Borrar la fila (y no marcarla usada) invalida el
     * hash viejo de forma incondicional y mantiene la invariante "una cuenta, una invitación viva".
     *
     * <p>Es un {@code DELETE} masivo ({@code @Modifying}) y no un borrado derivado por entidad a
     * propósito: Hibernate ordena las sentencias insert antes de delete, así que un borrado diferido
     * dejaría chocar el nuevo {@code user_id} contra el constraint único. Una sentencia masiva se
     * ejecuta al momento.
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM Invitation i WHERE i.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}

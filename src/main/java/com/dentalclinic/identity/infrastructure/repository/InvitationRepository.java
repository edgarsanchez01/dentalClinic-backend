package com.dentalclinic.identity.infrastructure.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dentalclinic.identity.domain.Invitation;

public interface InvitationRepository extends JpaRepository<Invitation, Long> {

    Optional<Invitation> findByTokenHash(String tokenHash);

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
}

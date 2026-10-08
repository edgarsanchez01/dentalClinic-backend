package com.dentalclinic.identity.infrastructure.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dentalclinic.identity.domain.PasswordResetToken;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    long deleteByUserId(Long userId);

    /**
     * Consumo atómico de un token de un solo uso (RF-IA-12, §24.6).
     *
     * @return 1 si este hilo consumió el token; 0 si ya se usó, expiró o no existe
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE PasswordResetToken t
               SET t.usedAt = :now
             WHERE t.tokenHash = :tokenHash
               AND t.usedAt IS NULL
               AND t.expiresAt > :now
            """)
    int consumeAtomically(@Param("tokenHash") String tokenHash, @Param("now") Instant now);
}

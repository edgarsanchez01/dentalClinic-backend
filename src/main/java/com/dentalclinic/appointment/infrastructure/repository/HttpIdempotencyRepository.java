package com.dentalclinic.appointment.infrastructure.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dentalclinic.appointment.domain.HttpIdempotency;

public interface HttpIdempotencyRepository extends JpaRepository<HttpIdempotency, Long> {

    /** A-16/O-2: la clave es única POR ACTOR Y OPERACIÓN. */
    @Query("select h from HttpIdempotency h "
            + "where h.actorUserId = :actorUserId and h.operation = :operation "
            + "and h.idempotencyKey = :idempotencyKey")
    Optional<HttpIdempotency> findByIdentity(@Param("actorUserId") Long actorUserId,
            @Param("operation") String operation, @Param("idempotencyKey") String idempotencyKey);
}
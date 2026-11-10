package com.dentalclinic.identity.infrastructure.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;

import jakarta.persistence.LockModeType;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.email = :email")
    Optional<User> findByEmailForUpdate(@Param("email") String email);

    boolean existsByEmail(String email);

    /**
     * Cuentas con el rol indicado bajo bloqueo pesimista de escritura (Fase 8A).
     *
     * <p>Serializa las transiciones de estado del último OWNER activo: dos desactivaciones
     * concurrentes no pueden ambas comprobar "queda otro OWNER activo" sobre el mismo snapshot y
     * dejar la instalación sin administrador. La segunda transacción espera el lock y relee el
     * resultado ya confirmado de la primera.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.role = :role ORDER BY u.id")
    List<User> findByRoleForUpdate(@Param("role") Role role);

    /** OWNER activos distintos del indicado; la guarda de "sin OWNER activo" (Fase 8A). */
    long countByRoleAndActiveTrueAndIdNot(Role role, Long id);
}

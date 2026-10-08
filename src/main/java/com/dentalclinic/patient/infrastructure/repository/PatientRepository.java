package com.dentalclinic.patient.infrastructure.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.dentalclinic.patient.domain.Patient;

public interface PatientRepository extends JpaRepository<Patient, Long> {

    Optional<Patient> findByUserId(Long userId);

    /**
     * Listado con búsqueda incremental (RF-PA-06) y filtro de vigencia (RF-PA-03).
     *
     * <p>Cada parámetro es opcional: {@code null} lo desactiva. La búsqueda es de subcadena, sin
     * distinguir mayúsculas, sobre nombre, apellido, email y teléfono. El patrón llega ya en
     * minúsculas y con comodines ({@code %término%}) desde el servicio, para que el índice no se
     * vea afectado por funciones sobre parámetros.
     */
    @Query("""
            SELECT p FROM Patient p
            WHERE (:active IS NULL OR p.active = :active)
              AND (:search IS NULL
                   OR LOWER(CONCAT(p.firstName, ' ', p.lastName)) LIKE :search
                   OR LOWER(p.firstName) LIKE :search
                   OR LOWER(p.lastName) LIKE :search
                   OR LOWER(COALESCE(p.email, '')) LIKE :search
                   OR COALESCE(p.phone, '') LIKE :search)
            """)
    Page<Patient> search(@Param("search") String search,
                         @Param("active") Boolean active,
                         Pageable pageable);
}
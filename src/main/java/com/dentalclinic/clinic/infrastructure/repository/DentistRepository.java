package com.dentalclinic.clinic.infrastructure.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dentalclinic.clinic.domain.Dentist;

public interface DentistRepository extends JpaRepository<Dentist, Long> {

    Optional<Dentist> findByUserId(Long userId);

    List<Dentist> findByUserIdIn(Collection<Long> userIds);

    boolean existsByUserId(Long userId);
}
package com.dentalclinic.clinic.infrastructure.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dentalclinic.clinic.domain.Clinic;

public interface ClinicRepository extends JpaRepository<Clinic, Long> {
}
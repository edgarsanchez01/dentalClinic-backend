package com.dentalclinic.identity.infrastructure.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dentalclinic.identity.domain.InstallationState;

public interface InstallationStateRepository extends JpaRepository<InstallationState, Short> {
}
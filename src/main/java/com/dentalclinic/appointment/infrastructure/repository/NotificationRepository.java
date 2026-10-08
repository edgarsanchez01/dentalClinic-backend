package com.dentalclinic.appointment.infrastructure.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dentalclinic.appointment.domain.Notification;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
}
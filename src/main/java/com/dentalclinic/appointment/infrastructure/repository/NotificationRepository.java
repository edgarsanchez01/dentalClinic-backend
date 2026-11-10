package com.dentalclinic.appointment.infrastructure.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.dentalclinic.appointment.domain.Notification;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** Buzón de un usuario (paginado). El llamador fija el orden; el buzón es siempre del propio usuario. */
    Page<Notification> findByUserId(Long userId, Pageable pageable);

    /** Contador de no leídas del usuario, apoyado en el índice parcial {@code notification_user_unread_idx}. */
    long countByUserIdAndReadAtIsNull(Long userId);
}

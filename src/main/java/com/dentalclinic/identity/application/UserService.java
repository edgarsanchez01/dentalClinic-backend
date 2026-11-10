package com.dentalclinic.identity.application;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;

/**
 * Lectura de cuentas para otros módulos (módulo→módulo vía servicio de aplicación, §6.2).
 *
 * <p>Solo expone consultas; ninguna operación de escritura sobre identidad. Las citas (Fase 6) lo
 * usan para comprobar la vigencia del profesional asignado y resolver el email de destino de una
 * comunicación sin acoplarse al repositorio de usuarios.
 */
@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public User getById(Long id) {
        return userRepository.findById(id).orElseThrow(NotFoundException::new);
    }

    /**
     * ¿La cuenta está activa? {@code false} si no existe. Es la única noción de "profesional
     * vigente" (el {@code Dentist} no duplica el flag, Architecture §15.2): la usan el directorio
     * público y la disponibilidad para no ofrecer profesionales jubilados o pendientes de activar.
     */
    @Transactional(readOnly = true)
    public boolean isActive(Long id) {
        return id != null && userRepository.findById(id).map(User::isActive).orElse(false);
    }

    /** Subconjunto de {@code userIds} cuyas cuentas siguen activas, en una sola consulta. */
    @Transactional(readOnly = true)
    public Set<Long> activeUserIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Set.of();
        }
        return userRepository.findAllById(userIds).stream()
                .filter(User::isActive)
                .map(User::getId)
                .collect(Collectors.toUnmodifiableSet());
    }
}
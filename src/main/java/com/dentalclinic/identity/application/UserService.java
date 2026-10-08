package com.dentalclinic.identity.application;

import java.util.Optional;

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
}
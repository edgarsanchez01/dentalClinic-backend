package com.dentalclinic.identity.application;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.clinic.application.DentistService;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;
import com.dentalclinic.patient.application.PatientService;

/**
 * Perfil autoritativo de la sesión (RF-IA-15, §14.3).
 *
 * <p>Desde Fase 4 resuelve el {@code patientId} real del usuario autenticado cuando existe un
 * {@code Patient} asociado, y devuelve vacío cuando no lo hay. La norma (§14.3) exige exactamente
 * eso: exponerlo <strong>solo si</strong> el usuario es PATIENT y tiene paciente asociado, y
 * <strong>no inventarlo ni inferirlo</strong> por heurística (P-08). La asociación se resolvió en
 * el registro público con el {@code userId} recién creado, nunca por coincidencia de email.
 *
 * <p>Desde Fase 6 (A-11) resuelve también el {@code dentistId} real cuando existe un {@code
 * Dentist} asociado (solo el personal: OWNER y DENTIST tienen perfil profesional). Nunca se
 * infiere ni si el usuario no es del personal.
 */
@Service
public class ProfileService {

    private final UserRepository userRepository;
    private final PatientService patientService;
    private final DentistService dentistService;

    public ProfileService(UserRepository userRepository, PatientService patientService,
                          DentistService dentistService) {
        this.userRepository = userRepository;
        this.patientService = patientService;
        this.dentistService = dentistService;
    }

    /**
     * @throws NotFoundException si el usuario autenticado ya no existe. No puede ocurrir con un
     *     access token válido —el filtro lo verifica en cada petición— pero tratarlo como 404
     *     evita un 500 si alguna vez ocurriera
     */
    @Transactional(readOnly = true)
    public UserProfile profileOf(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(NotFoundException::new);
        Optional<Long> patientId = patientService.findPatientIdForUser(user.getId());
        Long dentistId = user.getRole() == Role.OWNER || user.getRole() == Role.DENTIST
                ? dentistService.dentistIdFor(user.getId()).orElse(null)
                : null;
        return new UserProfile(user.getId(), user.getEmail(), user.getRole(), user.isActive(),
                user.getCreatedAt(), patientId.orElse(null), dentistId);
    }

    /**
     * @param email ya persistido en forma canónica ({@code trim + lowercase}); se devuelve tal
     *     cual, sin re-transformar
     * @param patientId paciente asociado, o {@code null} si el usuario no tiene ninguno
     * @param dentistId dentista asociado, o {@code null} si el usuario no tiene perfil profesional
     */
    public record UserProfile(Long userId, String email, Role role, boolean active, Instant createdAt,
                              Long patientId, Long dentistId) {
    }
}
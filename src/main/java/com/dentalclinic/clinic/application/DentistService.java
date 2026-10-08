package com.dentalclinic.clinic.application;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.clinic.domain.Dentist;
import com.dentalclinic.clinic.infrastructure.repository.DentistRepository;
import com.dentalclinic.identity.domain.exception.NotFoundException;

/**
 * Perfil profesional del dentista.
 *
 * <p>Se expone como servicio de aplicación para que los módulos de identidad (onboarding e
 * invitación de personal) creen el {@code Dentist} sin tocar su repositorio: la regla de
 * arquitectura es que los módulos se comunican por servicios de aplicación, no por repositorios
 * ajenos.
 */
@Service
public class DentistService {

    private final DentistRepository dentistRepository;

    public DentistService(DentistRepository dentistRepository) {
        this.dentistRepository = dentistRepository;
    }

    @Transactional
    public Dentist createForUser(Long userId, DentistData data) {
        return dentistRepository.save(Dentist.create(userId, data.firstName(), data.lastName(),
                data.phone(), data.specialty(), data.licenseNumber(), data.publicInfo(), data.photoUrl()));
    }

    @Transactional(readOnly = true)
    public Dentist getByUserId(Long userId) {
        return dentistRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("No existe un dentista para esa cuenta."));
    }

    @Transactional(readOnly = true)
    public Dentist get(Long id) {
        return dentistRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("No existe el dentista solicitado."));
    }

    /**
     * {@code dentistId} del perfil asociado a una cuenta, o vacío si el usuario no es del personal
     * clínico (A-11). No lanza: vacío significa "sin perfil profesional".
     */
    @Transactional(readOnly = true)
    public Optional<Long> dentistIdFor(Long userId) {
        return dentistRepository.findByUserId(userId).map(Dentist::getId);
    }

    /** Dentistas por id en un solo lote (para enriquecer agendas sin N+1). */
    @Transactional(readOnly = true)
    public Map<Long, Dentist> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return dentistRepository.findAllById(ids).stream()
                .collect(Collectors.toUnmodifiableMap(Dentist::getId, Function.identity()));
    }

    /** Perfiles indexados por {@code userId}, para enriquecer el listado de personal. */
    @Transactional(readOnly = true)
    public Map<Long, DentistProfile> profilesByUserIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return dentistRepository.findByUserIdIn(userIds).stream()
                .collect(Collectors.toUnmodifiableMap(Dentist::getUserId, DentistService::toProfile));
    }

    private static DentistProfile toProfile(Dentist dentist) {
        return new DentistProfile(dentist.getFirstName(), dentist.getLastName(), dentist.getPhone(),
                dentist.getSpecialty(), dentist.getLicenseNumber(), dentist.getPublicInfo(),
                dentist.getPhotoUrl());
    }

    /** Datos del perfil profesional que no viven en {@code User}. */
    public record DentistData(
            String firstName,
            String lastName,
            String phone,
            String specialty,
            String licenseNumber,
            String publicInfo,
            String photoUrl) {
    }

    public record DentistProfile(
            String firstName,
            String lastName,
            String phone,
            String specialty,
            String licenseNumber,
            String publicInfo,
            String photoUrl) {
    }
}
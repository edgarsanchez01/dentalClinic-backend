package com.dentalclinic.patient.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.patient.domain.Patient;
import com.dentalclinic.patient.infrastructure.repository.PatientRepository;

/**
 * Servicio de pacientes (módulo administrativo, Fase 4).
 *
 * <p>Es el punto de entrada del resto de módulos al agregado {@code Patient} (por ejemplo, el
 * registro público de identidad crea el paciente aquí, en su misma transacción). No expone ninguna
 * consulta clínica: el expediente vive en el módulo {@code clinical}.
 */
@Service
public class PatientService {

    private final PatientRepository patientRepository;
    private final Clock clock;

    public PatientService(PatientRepository patientRepository, Clock clock) {
        this.patientRepository = patientRepository;
        this.clock = clock;
    }

    /** Alta administrativa: paciente sin cuenta de usuario. */
    @Transactional
    public Patient create(PatientData data) {
        return patientRepository.save(Patient.create(
                required(data.firstName(), "firstName"),
                required(data.lastName(), "lastName"),
                optional(data.email()),
                optional(data.phone()),
                data.birthDate(),
                optional(data.administrativeNotes())));
    }

    /**
     * Alta de paciente con cuenta, usada por el registro público. El {@code userId} procede de la
     * cuenta recién creada y se pasa explícitamente; nunca se infiere.
     */
    @Transactional
    public Patient createForUser(Long userId, PatientData data) {
        return patientRepository.save(Patient.createForUser(userId,
                required(data.firstName(), "firstName"),
                required(data.lastName(), "lastName"),
                optional(data.email()),
                optional(data.phone()),
                data.birthDate(),
                optional(data.administrativeNotes())));
    }

    /** @throws NotFoundException si el paciente no existe */
    @Transactional(readOnly = true)
    public Patient get(Long id) {
        return patientRepository.findById(id).orElseThrow(NotFoundException::new);
    }

    @Transactional
    public Patient update(Long id, PatientData data, Boolean active) {
        Patient patient = get(id);
        patient.update(
                required(data.firstName(), "firstName"),
                required(data.lastName(), "lastName"),
                optional(data.email()),
                optional(data.phone()),
                data.birthDate(),
                optional(data.administrativeNotes()),
                active,
                clock.instant());
        return patient;
    }

    /** Listado administrativo con búsqueda incremental (RF-PA-06) y filtro de vigencia. */
    @Transactional(readOnly = true)
    public Page<Patient> search(String search, Boolean active, Pageable pageable) {
        return patientRepository.search(searchPattern(search), active, pageable);
    }

    /**
     * Resuelve el {@code patientId} real del usuario autenticado, o vacío si no tiene paciente
     * asociado. Nunca inventa ni infiere (P-08, §14.3).
     */
    @Transactional(readOnly = true)
    public Optional<Long> findPatientIdForUser(Long userId) {
        return patientRepository.findByUserId(userId).map(Patient::getId);
    }

    /** Paciente vinculado a una cuenta, si existe (vínculo 0..1, RF-IA-15/§14.3). */
    @Transactional(readOnly = true)
    public Optional<Patient> findByUserId(Long userId) {
        return patientRepository.findByUserId(userId);
    }

    /**
     * Pacientes por id en un solo lote (para enriquecer agendas sin N+1). Nunca lanza por faltantes:
     * el mapa solo contiene los que existen; quien lo use debe resolver los ausentes.
     */
    @Transactional(readOnly = true)
    public Map<Long, Patient> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return patientRepository.findAllById(ids).stream()
                .collect(Collectors.toUnmodifiableMap(Patient::getId, Function.identity()));
    }

    static String searchPattern(String term) {
        if (term == null || term.isBlank()) {
            return null;
        }
        return "%" + term.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }

    private static String optional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Datos administrativos editables. */
    public record PatientData(String firstName, String lastName, String email, String phone,
                              LocalDate birthDate, String administrativeNotes) {
    }
}
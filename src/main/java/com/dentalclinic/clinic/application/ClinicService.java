package com.dentalclinic.clinic.application;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.clinic.domain.Clinic;
import com.dentalclinic.clinic.domain.exception.InvalidClinicConfigurationException;
import com.dentalclinic.clinic.infrastructure.repository.ClinicRepository;
import com.dentalclinic.identity.domain.exception.NotFoundException;

/**
 * Lectura y actualización de la configuración única de la clínica.
 *
 * <p>La creación no vive aquí como operación pública: forma parte del onboarding (Fase 3), que la
 * invoca dentro de su propia transacción. Separar {@link #create} de {@link #update} permite que el
 * onboarding no pueda "actualizar" una clínica existente y que la única vía de creación sea la
 * configurada una sola vez.
 */
@Service
public class ClinicService {

    private final ClinicRepository clinicRepository;
    private final Clock clock;

    public ClinicService(ClinicRepository clinicRepository, Clock clock) {
        this.clinicRepository = clinicRepository;
        this.clock = clock;
    }

    @Transactional
    public Clinic create(ClinicData data) {
        validateTimezone(data.timezone());
        return clinicRepository.save(Clinic.create(data.name(), data.timezone(), data.description(),
                data.logoUrl(), data.address(), data.phone(), data.email(), data.publicInfo(),
                data.brandColor(), data.socialNetworks()));
    }

    @Transactional(readOnly = true)
    public Optional<Clinic> find() {
        return clinicRepository.findAll().stream().findFirst();
    }

    @Transactional(readOnly = true)
    public boolean exists() {
        return clinicRepository.count() > 0;
    }

    @Transactional(readOnly = true)
    public Clinic get() {
        return find().orElseThrow(() -> new NotFoundException("La clínica no está configurada."));
    }

    @Transactional
    public Clinic update(ClinicData data) {
        validateTimezone(data.timezone());
        Clinic clinic = get();
        clinic.update(data.name(), data.timezone(), data.description(), data.logoUrl(), data.address(),
                data.phone(), data.email(), data.publicInfo(), data.brandColor(),
                data.socialNetworks(), clock.instant());
        return clinic;
    }

    private void validateTimezone(String timezone) {
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException | NullPointerException e) {
            throw new InvalidClinicConfigurationException(
                    "La zona horaria indicada no es válida. Usa un identificador IANA, por ejemplo 'Europe/Madrid'.");
        }
    }

    /** Configuración completa de la clínica, tal como la recibe el onboarding o el PUT. */
    public record ClinicData(
            String name,
            String timezone,
            String description,
            String logoUrl,
            String address,
            String phone,
            String email,
            String publicInfo,
            String brandColor,
            Map<String, String> socialNetworks) {
    }
}
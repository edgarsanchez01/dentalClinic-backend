package com.dentalclinic.clinical.application;

import org.springframework.stereotype.Service;
import com.dentalclinic.clinic.application.DentistService;
import com.dentalclinic.clinic.domain.Dentist;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.scheduling.domain.ScheduleConflictException;

@Service
public class ClinicalAuthorService {
    private final DentistService dentists;
    public ClinicalAuthorService(DentistService dentists) { this.dentists = dentists; }

    public Dentist require(Long userId) {
        try { return dentists.getByUserId(userId); }
        catch (NotFoundException missing) {
            throw new ScheduleConflictException("Tu cuenta tiene permisos clínicos, pero le falta el perfil profesional necesario para registrar autoría. Debe completarse la configuración de la cuenta.");
        }
    }
}

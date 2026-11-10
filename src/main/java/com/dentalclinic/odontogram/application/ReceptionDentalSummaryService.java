package com.dentalclinic.odontogram.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.odontogram.domain.Dentition;
import com.dentalclinic.odontogram.domain.ToothCondition;
import com.dentalclinic.odontogram.domain.ToothNotation;
import com.dentalclinic.odontogram.domain.ToothSurface;
import com.dentalclinic.odontogram.infrastructure.repository.ReceptionDentalSummaryRepository;
import com.dentalclinic.patient.application.PatientService;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;

/** Single-clinic administrative patient scope, not PATIENT self-ownership or clinical access. */
@Service
@Transactional(readOnly = true)
public class ReceptionDentalSummaryService {
    private final PatientService patients;
    private final ReceptionDentalSummaryRepository summaries;

    public ReceptionDentalSummaryService(PatientService patients, ReceptionDentalSummaryRepository summaries) {
        this.patients = patients;
        this.summaries = summaries;
    }

    public List<ToothSummary> read(Long patientId, AuthenticatedUser actor) {
        if (actor == null || actor.role() != Role.RECEPTIONIST)
            throw new ForbiddenOperationException("No tienes acceso al resumen dental de recepción.");
        if (patientId == null || patientId <= 0) throw new NotFoundException();
        // Same existing patient scope as reception's administrative record (active or inactive).
        patients.get(patientId);
        var teeth = new LinkedHashMap<Integer, ToothSummary>();
        for (var row : summaries.findCurrentSummary(patientId)) {
            var tooth = teeth.computeIfAbsent(row.getToothFdi(), fdi ->
                    new ToothSummary(fdi, ToothNotation.dentitionOf(fdi), row.getCondition(), new ArrayList<>()));
            if (row.getSurface() != null)
                tooth.surfaces().add(new SurfaceSummary(row.getSurface(), row.getSurfaceCondition()));
        }
        return teeth.values().stream().map(t -> new ToothSummary(t.toothFdi(), t.dentition(),
                t.condition(), List.copyOf(t.surfaces()))).toList();
    }

    /** Explicit response fields: never serialize a clinical entity or full DTO. */
    public record ToothSummary(int toothFdi, Dentition dentition, ToothCondition condition,
                               List<SurfaceSummary> surfaces) { }
    public record SurfaceSummary(ToothSurface surface, ToothCondition condition) { }
}

package com.dentalclinic.scheduling.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.clinic.application.ClinicService;
import com.dentalclinic.clinic.domain.Clinic;
import com.dentalclinic.clinic.domain.Dentist;
import com.dentalclinic.clinic.infrastructure.repository.DentistRepository;
import com.dentalclinic.scheduling.api.dto.PublicDayView;
import com.dentalclinic.scheduling.api.dto.PublicDentistView;
import com.dentalclinic.scheduling.api.dto.PublicExceptionView;
import com.dentalclinic.scheduling.api.dto.PublicScheduleView;
import com.dentalclinic.scheduling.domain.ClinicSchedule;
import com.dentalclinic.scheduling.domain.ClinicScheduleException;
import com.dentalclinic.scheduling.domain.DentistSchedule;
import com.dentalclinic.scheduling.infrastructure.repository.ClinicScheduleExceptionRepository;
import com.dentalclinic.scheduling.infrastructure.repository.ClinicScheduleRepository;
import com.dentalclinic.scheduling.infrastructure.repository.DentistScheduleRepository;

/**
 * Horario público (RF-SC-08). Expone solo ventanas semanales y excepciones de la clínica
 * <strong>sin motivo</strong>. Nunca breaks, franquicias, motivos de dentista ni datos privados.
 */
@Service
public class PublicScheduleService {

    private final ClinicService clinicService;
    private final DentistRepository dentistRepository;
    private final ClinicScheduleRepository clinicScheduleRepository;
    private final ClinicScheduleExceptionRepository clinicExceptionRepository;
    private final DentistScheduleRepository dentistScheduleRepository;

    public PublicScheduleService(ClinicService clinicService, DentistRepository dentistRepository,
            ClinicScheduleRepository clinicScheduleRepository,
            ClinicScheduleExceptionRepository clinicExceptionRepository,
            DentistScheduleRepository dentistScheduleRepository) {
        this.clinicService = clinicService;
        this.dentistRepository = dentistRepository;
        this.clinicScheduleRepository = clinicScheduleRepository;
        this.clinicExceptionRepository = clinicExceptionRepository;
        this.dentistScheduleRepository = dentistScheduleRepository;
    }

    @Transactional(readOnly = true)
    public PublicScheduleView get() {
        Clinic clinic = clinicService.get();
        Long clinicId = clinic.getId();

        Map<Integer, ClinicSchedule> clinicWeekly = new HashMap<>();
        for (ClinicSchedule s : clinicScheduleRepository.findByClinicIdOrderByDayOfWeekAsc(clinicId)) {
            clinicWeekly.put(s.getDayOfWeek(), s);
        }
        List<PublicDayView> clinicDays = clinicDays(clinicWeekly);

        List<PublicExceptionView> exceptions = new ArrayList<>();
        for (ClinicScheduleException e : clinicExceptionRepository
                .findByClinicIdOrderByExceptionDateAsc(clinicId)) {
            exceptions.add(new PublicExceptionView(e.getExceptionDate(), e.isClosed(), e.getStartTime(),
                    e.getEndTime()));
        }

        List<Dentist> dentists = new ArrayList<>(dentistRepository.findAll());
        dentists.sort(Comparator.comparing(Dentist::getLastName).thenComparing(Dentist::getFirstName));
        List<PublicDentistView> dentistViews = new ArrayList<>();
        for (Dentist dentist : dentists) {
            Map<Integer, DentistSchedule> weekly = new HashMap<>();
            for (DentistSchedule s : dentistScheduleRepository
                    .findByDentistIdOrderByDayOfWeekAsc(dentist.getId())) {
                weekly.put(s.getDayOfWeek(), s);
            }
            dentistViews.add(new PublicDentistView(dentist.getId(), dentist.getFirstName(),
                    dentist.getLastName(), dentist.getSpecialty(), dentist.getPhotoUrl(),
                    dentistDays(weekly)));
        }

        return new PublicScheduleView(clinic.getTimezone(), clinicDays, exceptions, dentistViews);
    }

    private static List<PublicDayView> clinicDays(Map<Integer, ClinicSchedule> weekly) {
        List<PublicDayView> days = new ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            ClinicSchedule s = weekly.get(d);
            days.add(s == null
                    ? new PublicDayView(d, false, null, null)
                    : new PublicDayView(d, true, s.getStartTime(), s.getEndTime()));
        }
        return days;
    }

    private static List<PublicDayView> dentistDays(Map<Integer, DentistSchedule> weekly) {
        List<PublicDayView> days = new ArrayList<>();
        for (int d = 1; d <= 7; d++) {
            DentistSchedule s = weekly.get(d);
            days.add(s == null
                    ? new PublicDayView(d, false, null, null)
                    : new PublicDayView(d, true, s.getStartTime(), s.getEndTime()));
        }
        return days;
    }
}
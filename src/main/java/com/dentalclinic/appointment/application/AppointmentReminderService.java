package com.dentalclinic.appointment.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.appointment.domain.AppointmentStatus;
import com.dentalclinic.appointment.domain.CommunicationIntention.RequiredRole;
import com.dentalclinic.appointment.domain.CommunicationType;
import com.dentalclinic.appointment.infrastructure.repository.AppointmentRepository;
import com.dentalclinic.appointment.infrastructure.repository.CommunicationIntentionRepository;
import com.dentalclinic.clinic.application.ClinicService;
import com.dentalclinic.clinic.application.DentistService;
import com.dentalclinic.patient.application.PatientService;

/** Materializa los dos canales de una ocurrencia en un único commit, sin envío externo. */
@Service
public class AppointmentReminderService {

    static final Duration LEAD_TIME = Duration.ofHours(5);

    private final AppointmentRepository appointments;
    private final CommunicationIntentionRepository intentions;
    private final PatientService patients;
    private final ClinicService clinic;
    private final AppointmentCommunicationPlanner planner;
    private final Clock clock;
    private final DentistService dentists;

    public AppointmentReminderService(AppointmentRepository appointments,
            CommunicationIntentionRepository intentions, PatientService patients,
            ClinicService clinic, AppointmentCommunicationPlanner planner, Clock clock, DentistService dentists) {
        this.appointments = appointments;
        this.intentions = intentions;
        this.patients = patients;
        this.clinic = clinic;
        this.planner = planner;
        this.clock = clock;
        this.dentists = dentists;
    }

    /** El proxy abre una transacción independiente incluso si el llamador ya tiene una. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean generate(Long appointmentId, int expectedVersion) {
        Appointment appointment = appointments.findByIdForUpdate(appointmentId).orElse(null);
        Instant now = clock.instant();
        if (appointment == null || appointment.getStatus() != AppointmentStatus.SCHEDULED
                || appointment.getOccurrenceVersion() != expectedVersion
                || !appointment.getStartAt().isAfter(now)
                || appointment.getStartAt().isAfter(now.plus(LEAD_TIME))) {
            return false;
        }
        if (intentions.existsByAppointmentIdAndEventTypeAndOccurrenceVersionAndRequiredRole(
                appointmentId, CommunicationType.REMINDER, expectedVersion, RequiredRole.PATIENT)) {
            return false;
        }
        planner.reminder(appointment, patients.get(appointment.getPatientId()), clinic.get(),
                dentists.get(appointment.getDentistId()));
        return true;
    }
}

package com.dentalclinic.appointment.application;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.appointment.infrastructure.repository.AppointmentRepository;

/** Implementacion package-private del puerto de lectura de citas para checkout. */
@Service
@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
class AppointmentCheckoutReferenceReaderImpl implements AppointmentCheckoutReferenceReader {

    private final AppointmentRepository appointments;

    AppointmentCheckoutReferenceReaderImpl(AppointmentRepository appointments) {
        this.appointments = appointments;
    }

    @Override
    public Optional<Reference> findById(Long appointmentId) {
        return appointments.findById(appointmentId)
                .map(AppointmentCheckoutReferenceReaderImpl::reference);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Reference> lockById(Long appointmentId) {
        return appointments.findByIdForUpdate(appointmentId)
                .map(AppointmentCheckoutReferenceReaderImpl::reference);
    }

    private static Reference reference(com.dentalclinic.appointment.domain.Appointment appointment) {
        return new Reference(appointment.getId(), appointment.getPatientId(),
                appointment.getOccurrenceVersion());
    }
}

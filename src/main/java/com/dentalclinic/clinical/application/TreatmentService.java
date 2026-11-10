package com.dentalclinic.clinical.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.appointment.application.AppointmentService;
import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.clinical.domain.InvalidTreatmentException;
import com.dentalclinic.clinical.domain.Treatment;
import com.dentalclinic.clinical.domain.TreatmentStatus;
import com.dentalclinic.clinical.infrastructure.repository.TreatmentRepository;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.patient.application.PatientService;

/**
 * Registro clinico basico de tratamientos (RF-TR-04/05/06, F7.4.1).
 *
 * <p>La autorizacion de rol la aplica la cadena de seguridad (matriz 7.4): el grupo
 * {@code /api/patients/*&#47;clinical/**} da escritura a OWNER y DENTIST (el OWNER siempre tiene
 * perfil de dentista, AD-11). Este servicio asume que quien escribe es personal clinico; aqui solo
 * se resuelve la existencia del paciente, la referencia opcional a una cita y la coherencia del
 * contenido clinico.
 *
 * <p>No implementa odontograma/dientes, evolucion historica, presupuestos, multisesion, pagos ni
 * cargos (diferidos a F7.4.2 o fases posteriores).
 */
@Service
public class TreatmentService {

    private final PatientService patientService;
    private final AppointmentService appointmentService;
    private final TreatmentRepository treatmentRepository;
    private final Clock clock;

    public TreatmentService(PatientService patientService, AppointmentService appointmentService,
                            TreatmentRepository treatmentRepository, Clock clock) {
        this.patientService = patientService;
        this.appointmentService = appointmentService;
        this.treatmentRepository = treatmentRepository;
        this.clock = clock;
    }

    /** Tratamientos del paciente, del mas reciente al mas antiguo. */
    @Transactional(readOnly = true)
    public List<Treatment> list(Long patientId) {
        patientService.get(patientId);
        return treatmentRepository.findByPatientIdOrderByCreatedAtDescIdDesc(patientId);
    }

    /** @throws NotFoundException si el paciente o el tratamiento no existen (o no es del paciente) */
    @Transactional(readOnly = true)
    public Treatment get(Long patientId, Long treatmentId) {
        patientService.get(patientId);
        return treatmentRepository.findByIdAndPatientId(treatmentId, patientId)
                .orElseThrow(NotFoundException::new);
    }

    /** Crea un tratamiento para el paciente, validando la cita opcional que se le asocie. */
    @Transactional
    public Treatment create(Long patientId, TreatmentData data, AuthenticatedUser actor) {
        patientService.get(patientId);
        assertAppointmentBelongsToPatient(patientId, data.appointmentId(), actor);
        Treatment treatment = Treatment.create(patientId, data.appointmentId(), data.description(),
                data.diagnosis(), data.status(), data.notes(), data.price(), clock.instant());
        return treatmentRepository.save(treatment);
    }

    /** Actualiza el contenido clinico y el estado de un tratamiento existente del paciente. */
    @Transactional
    public Treatment update(Long patientId, Long treatmentId, TreatmentData data) {
        Treatment treatment = get(patientId, treatmentId);
        treatment.update(data.description(), data.diagnosis(), data.status(), data.notes(),
                data.price(), clock.instant());
        return treatment;
    }

    /**
     * RF-TR-05: la cita referenciada debe existir y pertenecer al mismo paciente.
     *
     * <p>La cita se resuelve como <strong>referencia clínica</strong>
     * ({@link AppointmentService#resolveReference}), no por alcance de agenda: el expediente es
     * longitudinal ({@code P-05}) y un profesional puede vincular un tratamiento a una cita del
     * mismo paciente aunque la haya atendido otro profesional.
     *
     * <p>La resolución no aplica por sí sola ningún permiso: este consumidor comprueba las dos
     * condiciones que sí importan aquí, es decir, el rol de personal clínico y la pertenencia de la
     * cita al paciente del tratamiento.
     */
    private void assertAppointmentBelongsToPatient(Long patientId, Long appointmentId,
                                                   AuthenticatedUser actor) {
        requireClinicalStaff(actor);
        if (appointmentId == null) {
            return;
        }
        Appointment appointment = appointmentService.resolveReference(appointmentId);
        if (!patientId.equals(appointment.getPatientId())) {
            throw new InvalidTreatmentException("La cita indicada no pertenece al paciente.");
        }
    }

    /**
     * Escritura clínica: solo {@code OWNER} y {@code DENTIST} (matriz §7.4, {@code P-05}). La
     * cadena de URL ya lo restringe; aquí se repite para que una invocación directa del servicio
     * no convierta la referencia a una cita en una vía de acceso.
     */
    private static void requireClinicalStaff(AuthenticatedUser actor) {
        if (actor == null || (actor.role() != Role.OWNER && actor.role() != Role.DENTIST)) {
            throw new ForbiddenOperationException(
                    "Solo el personal clínico puede registrar tratamientos.");
        }
    }

    /** Contenido clinico editable de un tratamiento. */
    public record TreatmentData(String description, String diagnosis, TreatmentStatus status,
                                Long appointmentId, String notes, BigDecimal price) {
    }
}

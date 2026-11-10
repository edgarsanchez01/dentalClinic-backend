package com.dentalclinic.financial.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.appointment.application.AppointmentService;
import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.financial.domain.InvalidChargeException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.patient.application.PatientService;

/**
 * Valida las referencias administrativas de un cargo (Fase 2A).
 *
 * <p>Nada de esto ocurre en el cliente: se comprueba siempre contra datos persistidos.
 *
 * <ul>
 *   <li>{@code patient_id} debe corresponder a un paciente real.</li>
 *   <li>Si el cargo referencia una cita, esa cita debe existir y ser del MISMO paciente.</li>
 *   <li>Validar referencias no crea cargos, no genera movimientos y no cambia el estado de la
 *       cita (una cita {@code COMPLETED} permanece completada).</li>
 * </ul>
 *
 * <p>Reutiliza {@link AppointmentService} para resolver y comprobar visibilidad de la cita, igual
 * que el registro clinico de tratamientos (RF-TR-05; RF-PY-06).
 */
@Service
public class ChargeReferenceValidator {

    private final PatientService patientService;
    private final AppointmentService appointmentService;

    public ChargeReferenceValidator(PatientService patientService,
                                    AppointmentService appointmentService) {
        this.patientService = patientService;
        this.appointmentService = appointmentService;
    }

    /**
     * @param patientId     paciente del cargo; obligatorio
     * @param appointmentId cita opcional vinculada; puede ser {@code null}
     * @throws com.dentalclinic.identity.domain.exception.NotFoundException si el paciente o la cita
     *         no existen (o la cita no es visible para el actor)
     * @throws InvalidChargeException si la cita no pertenece al paciente del cargo
     */
    @Transactional(readOnly = true)
    public void requireValidReferences(Long patientId, Long appointmentId, AuthenticatedUser actor) {
        if (patientId == null) {
            throw new InvalidChargeException("El paciente del cargo es obligatorio.");
        }
        patientService.get(patientId);
        if (appointmentId == null) {
            return;
        }
        Appointment appointment = appointmentService.get(appointmentId, actor);
        if (!patientId.equals(appointment.getPatientId())) {
            throw new InvalidChargeException(
                    "La cita indicada no pertenece al paciente del cargo.");
        }
    }
}

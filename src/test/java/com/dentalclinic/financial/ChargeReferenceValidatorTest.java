package com.dentalclinic.financial;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dentalclinic.appointment.application.AppointmentService;
import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.financial.application.ChargeReferenceValidator;
import com.dentalclinic.financial.domain.InvalidChargeException;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.patient.application.PatientService;

class ChargeReferenceValidatorTest {

    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");

    private static final Long PATIENT_ID = 1L;
    private static final Long OTHER_PATIENT_ID = 2L;
    private static final Long APPOINTMENT_ID = 55L;

    private PatientService patientService;
    private AppointmentService appointmentService;
    private ChargeReferenceValidator validator;

    private final AuthenticatedUser actor = new AuthenticatedUser(1L, Role.RECEPTIONIST);

    @BeforeEach
    void setUp() {
        patientService = mock(PatientService.class);
        appointmentService = mock(AppointmentService.class);
        validator = new ChargeReferenceValidator(patientService, appointmentService);
    }

    @Test
    void cargo_sin_cita_solo_valida_el_paciente() {
        validator.requireValidReferences(PATIENT_ID, null, actor);

        verify(patientService).get(PATIENT_ID);
        verifyNoInteractions(appointmentService);
    }

    @Test
    void cita_del_mismo_paciente_es_valida() {
        when(appointmentService.get(APPOINTMENT_ID, actor))
                .thenReturn(appointmentOf(PATIENT_ID));

        validator.requireValidReferences(PATIENT_ID, APPOINTMENT_ID, actor);
        verify(patientService).get(PATIENT_ID);
    }

    @Test
    void cita_de_otro_paciente_es_invalida() {
        when(appointmentService.get(APPOINTMENT_ID, actor))
                .thenReturn(appointmentOf(OTHER_PATIENT_ID));

        assertThatThrownBy(() ->
                validator.requireValidReferences(PATIENT_ID, APPOINTMENT_ID, actor))
                .isInstanceOf(InvalidChargeException.class)
                .hasMessageContaining("no pertenece");
    }

    @Test
    void paciente_inexistente_se_rechaza() {
        when(patientService.get(PATIENT_ID)).thenThrow(new NotFoundException());

        assertThatThrownBy(() -> validator.requireValidReferences(PATIENT_ID, null, actor))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(appointmentService);
    }

    @Test
    void cita_inexistente_se_rechaza() {
        when(appointmentService.get(APPOINTMENT_ID, actor)).thenThrow(new NotFoundException());

        assertThatThrownBy(() ->
                validator.requireValidReferences(PATIENT_ID, APPOINTMENT_ID, actor))
                .isInstanceOf(NotFoundException.class);
    }

    private static Appointment appointmentOf(Long patientId) {
        return Appointment.create(patientId, 7L, 9L, NOW, NOW.plusSeconds(3600), 60, "Consulta",
                null);
    }

    @Test
    void paciente_nulo_se_rechaza_antes_de_consultar() {
        assertThatThrownBy(() -> validator.requireValidReferences(null, APPOINTMENT_ID, actor))
                .isInstanceOf(InvalidChargeException.class);
        verifyNoInteractions(patientService, appointmentService);
    }

    @Test
    void validar_cita_completada_no_cambia_su_estado() {
        Appointment appointment = appointmentOf(PATIENT_ID);
        appointment.complete(NOW);
        when(appointmentService.get(APPOINTMENT_ID, actor)).thenReturn(appointment);

        validator.requireValidReferences(PATIENT_ID, APPOINTMENT_ID, actor);

        org.assertj.core.api.Assertions.assertThat(appointment.getStatus())
                .isEqualTo(com.dentalclinic.appointment.domain.AppointmentStatus.COMPLETED);
        org.assertj.core.api.Assertions.assertThat(appointment.getCompletedAt()).isEqualTo(NOW);
    }
}

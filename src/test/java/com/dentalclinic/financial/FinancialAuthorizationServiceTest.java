package com.dentalclinic.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.dentalclinic.appointment.application.AppointmentService;
import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.clinic.application.DentistService;
import com.dentalclinic.financial.application.FinancialAuthorizationService;
import com.dentalclinic.financial.application.command.ChargeItemData;
import com.dentalclinic.financial.application.command.ConfirmChargeCommand;
import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.ChargeItem;
import com.dentalclinic.financial.infrastructure.repository.ChargeItemRepository;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

class FinancialAuthorizationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
    private static final Long DENTIST_USER_ID = 10L;
    private static final Long OWN_DENTIST_ID = 7L;
    private static final Long OTHER_DENTIST_ID = 8L;
    private static final Long APPOINTMENT_ID = 55L;
    private static final Long CHARGE_ID = 100L;

    private DentistService dentistService;
    private AppointmentService appointmentService;
    private ChargeItemRepository chargeItemRepository;
    private FinancialAuthorizationService authorization;

    private final AuthenticatedUser owner = new AuthenticatedUser(1L, Role.OWNER);
    private final AuthenticatedUser receptionist = new AuthenticatedUser(2L, Role.RECEPTIONIST);
    private final AuthenticatedUser dentist = new AuthenticatedUser(DENTIST_USER_ID, Role.DENTIST);
    private final AuthenticatedUser patient = new AuthenticatedUser(3L, Role.PATIENT);

    @BeforeEach
    void setUp() {
        dentistService = mock(DentistService.class);
        appointmentService = mock(AppointmentService.class);
        chargeItemRepository = mock(ChargeItemRepository.class);
        authorization = new FinancialAuthorizationService(dentistService, appointmentService,
                chargeItemRepository);
    }

    private Charge chargeWithAppointment() {
        Charge charge = Charge.createConfirmed(1L, APPOINTMENT_ID, new BigDecimal("1000.00"), null,
                1L, NOW);
        ReflectionTestUtils.setField(charge, "id", CHARGE_ID);
        return charge;
    }

    private void stubOwnAppointment() {
        when(dentistService.dentistIdFor(DENTIST_USER_ID)).thenReturn(Optional.of(OWN_DENTIST_ID));
        when(appointmentService.get(APPOINTMENT_ID, dentist))
                .thenReturn(Appointment.create(1L, OWN_DENTIST_ID, DENTIST_USER_ID, NOW,
                        NOW.plusSeconds(3600), 60, "Consulta", null));
    }

    @Test
    void owner_y_receptionist_ven_cualquier_cargo_sin_resolver_vinculos() {
        Charge charge = chargeWithAppointment();

        authorization.requireView(owner, charge);
        authorization.requireView(receptionist, charge);

        verifyNoInteractions(dentistService, appointmentService, chargeItemRepository);
    }

    @Test
    void owner_y_receptionist_gestionan_y_solo_owner_anula_y_reversa() {
        ConfirmChargeCommand command = command(null, null);
        authorization.requireCreateCharge(owner, command);
        authorization.requireRegisterPayment(owner, chargeWithAppointment());
        authorization.requireCancel(owner);
        authorization.requireReverse(owner);

        authorization.requireCreateCharge(receptionist, command);
        authorization.requireRegisterPayment(receptionist, chargeWithAppointment());
        assertThatThrownBy(() -> authorization.requireCancel(receptionist))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> authorization.requireReverse(receptionist))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void dentist_no_anula_ni_reversa() {
        assertThatThrownBy(() -> authorization.requireCancel(dentist))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> authorization.requireReverse(dentist))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void patient_no_tiene_acceso_financiero() {
        assertThatThrownBy(() -> authorization.requireView(patient, chargeWithAppointment()))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> authorization.requireCreateCharge(patient, command(null, null)))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> authorization.requireRegisterPayment(patient, chargeWithAppointment()))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> authorization.requireCancel(patient))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> authorization.requireReverse(patient))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void dentist_ve_cargo_de_su_propia_cita() {
        Charge charge = chargeWithAppointment();
        stubOwnAppointment();
        when(chargeItemRepository.findByChargeIdOrderByIdAsc(CHARGE_ID)).thenReturn(List.of(
                ChargeItem.create(CHARGE_ID, null, OWN_DENTIST_ID, "Limpieza", 1,
                        new BigDecimal("1000.00"), NOW)));

        authorization.requireView(dentist, charge);

        assertThat(authorization.canView(dentist, charge)).isTrue();
    }

    @Test
    void dentist_ve_cargo_de_su_cita_sin_treating_dentist_en_conceptos() {
        Charge charge = chargeWithAppointment();
        stubOwnAppointment();
        when(chargeItemRepository.findByChargeIdOrderByIdAsc(CHARGE_ID)).thenReturn(List.of(
                ChargeItem.create(CHARGE_ID, null, null, "Consulta", 1,
                        new BigDecimal("1000.00"), NOW)));

        authorization.requireView(dentist, charge);
    }

    @Test
    void dentist_no_ve_cargo_de_cita_de_otro_dentista() {
        Charge charge = chargeWithAppointment();
        when(dentistService.dentistIdFor(DENTIST_USER_ID)).thenReturn(Optional.of(OWN_DENTIST_ID));
        when(appointmentService.get(APPOINTMENT_ID, dentist))
                .thenReturn(Appointment.create(1L, OTHER_DENTIST_ID, 99L, NOW, NOW.plusSeconds(3600),
                        60, "Consulta", null));

        assertThatThrownBy(() -> authorization.requireView(dentist, charge))
                .isInstanceOf(NotFoundException.class);
        assertThat(authorization.canView(dentist, charge)).isFalse();
    }

    @Test
    void dentist_no_ve_cargo_sin_cita_fail_closed() {
        Charge charge = Charge.createConfirmed(1L, null, new BigDecimal("1000.00"), null, 1L, NOW);
        ReflectionTestUtils.setField(charge, "id", CHARGE_ID);
        when(dentistService.dentistIdFor(DENTIST_USER_ID)).thenReturn(Optional.of(OWN_DENTIST_ID));

        assertThatThrownBy(() -> authorization.requireView(dentist, charge))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(appointmentService, chargeItemRepository);
    }

    @Test
    void dentist_no_ve_cargo_mixto_de_varios_dentistas() {
        Charge charge = chargeWithAppointment();
        stubOwnAppointment();
        when(chargeItemRepository.findByChargeIdOrderByIdAsc(CHARGE_ID)).thenReturn(List.of(
                ChargeItem.create(CHARGE_ID, null, OWN_DENTIST_ID, "Limpieza", 1,
                        new BigDecimal("500.00"), NOW),
                ChargeItem.create(CHARGE_ID, null, OTHER_DENTIST_ID, "Extraccion", 1,
                        new BigDecimal("500.00"), NOW)));

        assertThatThrownBy(() -> authorization.requireView(dentist, charge))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void dentist_sin_perfil_profesional_no_ve_nada() {
        Charge charge = chargeWithAppointment();
        when(dentistService.dentistIdFor(DENTIST_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authorization.requireView(dentist, charge))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(appointmentService, chargeItemRepository);
        verify(dentistService).dentistIdFor(DENTIST_USER_ID);
    }

    @Test
    void dentist_no_crea_cargo_aunque_sea_de_su_cita() {
        stubOwnAppointment();

        assertThatThrownBy(() -> authorization.requireCreateCharge(dentist,
                command(APPOINTMENT_ID, OWN_DENTIST_ID)))
                .isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(dentistService, appointmentService, chargeItemRepository);
    }

    @Test
    void dentist_no_crea_cargos_mixtos_ni_con_conceptos_ajenos() {
        ConfirmChargeCommand mixed = new ConfirmChargeCommand(1L, APPOINTMENT_ID,
                List.of(concept(OWN_DENTIST_ID), concept(OTHER_DENTIST_ID)), null, "scope");

        assertThatThrownBy(() -> authorization.requireCreateCharge(dentist, mixed))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> authorization.requireCreateCharge(dentist,
                command(APPOINTMENT_ID, OTHER_DENTIST_ID)))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void dentist_id_del_cliente_no_autoriza_cargo_sin_cita() {
        assertThatThrownBy(() -> authorization.requireCreateCharge(dentist,
                command(null, OWN_DENTIST_ID)))
                .isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(dentistService, appointmentService, chargeItemRepository);
    }

    @Test
    void dentist_no_registra_pagos_aunque_sea_su_cargo() {
        Charge charge = chargeWithAppointment();
        stubOwnAppointment();
        when(chargeItemRepository.findByChargeIdOrderByIdAsc(CHARGE_ID)).thenReturn(List.of(
                ChargeItem.create(CHARGE_ID, null, OWN_DENTIST_ID, "Limpieza", 1,
                        new BigDecimal("1000.00"), NOW)));

        assertThatThrownBy(() -> authorization.requireRegisterPayment(dentist, charge))
                .isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(dentistService, appointmentService, chargeItemRepository);
    }

    @Test
    void dentist_no_registra_pagos_en_cargo_ajeno_ni_mixto() {
        stubOwnAppointment();
        Charge charge = Charge.createConfirmed(2L, APPOINTMENT_ID, BigDecimal.TEN, null, 1L, NOW);
        ReflectionTestUtils.setField(charge, "id", CHARGE_ID);

        assertThatThrownBy(() -> authorization.requireRegisterPayment(dentist, charge))
                .isInstanceOf(ForbiddenOperationException.class);

        when(chargeItemRepository.findByChargeIdOrderByIdAsc(CHARGE_ID)).thenReturn(List.of(
                ChargeItem.create(CHARGE_ID, null, OTHER_DENTIST_ID, "Limpieza", 1,
                        new BigDecimal("1000.00"), NOW)));
        assertThatThrownBy(() -> authorization.requireRegisterPayment(dentist, chargeWithAppointment()))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void dentist_sin_perfil_no_crea_ni_registra_pagos() {
        assertThatThrownBy(() -> authorization.requireCreateCharge(dentist,
                command(APPOINTMENT_ID, OWN_DENTIST_ID)))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> authorization.requireRegisterPayment(dentist, chargeWithAppointment()))
                .isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(dentistService, appointmentService, chargeItemRepository);
    }

    @Test
    void dentist_sin_cita_existente_no_consulta_ni_crea_ni_registra_pagos() {
        when(dentistService.dentistIdFor(DENTIST_USER_ID)).thenReturn(Optional.of(OWN_DENTIST_ID));
        when(appointmentService.get(APPOINTMENT_ID, dentist)).thenThrow(new NotFoundException());

        assertThatThrownBy(() -> authorization.requireView(dentist, chargeWithAppointment()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> authorization.requireRegisterPayment(dentist, chargeWithAppointment()))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> authorization.requireCreateCharge(dentist,
                command(APPOINTMENT_ID, OWN_DENTIST_ID)))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void dentist_sin_conceptos_verificables_no_consulta_y_no_escribe() {
        stubOwnAppointment();
        when(chargeItemRepository.findByChargeIdOrderByIdAsc(CHARGE_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> authorization.requireView(dentist, chargeWithAppointment()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> authorization.requireRegisterPayment(dentist, chargeWithAppointment()))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> authorization.requireCreateCharge(dentist,
                new ConfirmChargeCommand(1L, APPOINTMENT_ID, List.of(), null, "scope")))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> authorization.requireCreateCharge(dentist,
                new ConfirmChargeCommand(1L, APPOINTMENT_ID, null, null, "scope")))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void identidad_ausente_o_incompleta_es_denegada_en_todas_las_operaciones() {
        for (AuthenticatedUser actor : java.util.Arrays.asList(null,
                new AuthenticatedUser(null, Role.OWNER), new AuthenticatedUser(1L, null))) {
            assertThatThrownBy(() -> authorization.requireView(actor, chargeWithAppointment()))
                    .isInstanceOf(ForbiddenOperationException.class);
            assertThatThrownBy(() -> authorization.requireCreateCharge(actor, command(null, null)))
                    .isInstanceOf(ForbiddenOperationException.class);
            assertThatThrownBy(() -> authorization.requireRegisterPayment(actor, chargeWithAppointment()))
                    .isInstanceOf(ForbiddenOperationException.class);
            assertThatThrownBy(() -> authorization.requireCancel(actor))
                    .isInstanceOf(ForbiddenOperationException.class);
            assertThatThrownBy(() -> authorization.requireReverse(actor))
                    .isInstanceOf(ForbiddenOperationException.class);
        }
        verifyNoInteractions(dentistService, appointmentService, chargeItemRepository);
    }

    @Test
    void conceptos_autorizados_no_pueden_mutarse_a_cargo_mixto() {
        java.util.ArrayList<ChargeItemData> items = new java.util.ArrayList<>(List.of(concept(OWN_DENTIST_ID)));
        ConfirmChargeCommand command = new ConfirmChargeCommand(1L, APPOINTMENT_ID, items, null, "scope");
        items.add(concept(OTHER_DENTIST_ID));

        assertThat(command.items()).hasSize(1);
        assertThatThrownBy(() -> command.items().add(concept(OTHER_DENTIST_ID)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static ConfirmChargeCommand command(Long appointmentId, Long treatingDentistId) {
        return new ConfirmChargeCommand(1L, appointmentId, List.of(concept(treatingDentistId)), null, "scope");
    }

    private static ChargeItemData concept(Long treatingDentistId) {
        return new ChargeItemData(null, treatingDentistId, "Consulta", 1, BigDecimal.TEN);
    }
}

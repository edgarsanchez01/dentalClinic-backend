package com.dentalclinic.financial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.dentalclinic.financial.application.ChargeFinancialStateService;
import com.dentalclinic.financial.application.FinancialAuthorizationService;
import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.ChargeFinancialState;
import com.dentalclinic.financial.domain.ChargeStatus;
import com.dentalclinic.financial.infrastructure.repository.ChargeRepository;
import com.dentalclinic.financial.infrastructure.repository.FinancialMovementRepository;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

class ChargeFinancialStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
    private static final Long CHARGE_ID = 100L;

    private ChargeRepository chargeRepository;
    private FinancialMovementRepository movementRepository;
    private FinancialAuthorizationService authorization;
    private ChargeFinancialStateService service;

    private final AuthenticatedUser receptionist = new AuthenticatedUser(2L, Role.RECEPTIONIST);

    @BeforeEach
    void setUp() {
        chargeRepository = mock(ChargeRepository.class);
        movementRepository = mock(FinancialMovementRepository.class);
        authorization = mock(FinancialAuthorizationService.class);
        service = new ChargeFinancialStateService(chargeRepository, movementRepository, authorization);
    }

    private Charge charge(String total) {
        Charge charge = Charge.createConfirmed(1L, null, new BigDecimal(total), null, 1L, NOW);
        ReflectionTestUtils.setField(charge, "id", CHARGE_ID);
        when(chargeRepository.findById(CHARGE_ID)).thenReturn(Optional.of(charge));
        return charge;
    }

    @Test
    void sin_movimientos_es_pendiente() {
        when(movementRepository.sumValidAppliedAmount(CHARGE_ID))
                .thenReturn(Optional.empty());

        charge("1000.00");
        ChargeFinancialState state = service.stateOf(CHARGE_ID, receptionist);

        assertThat(state.status()).isEqualTo(ChargeStatus.PENDIENTE);
        assertThat(state.pendingBalance()).isEqualByComparingTo("1000.00");
    }

    @Test
    void con_abono_parcial_es_parcial() {
        when(movementRepository.sumValidAppliedAmount(CHARGE_ID))
                .thenReturn(Optional.of(new BigDecimal("250.00")));

        charge("1000.00");
        ChargeFinancialState state = service.stateOf(CHARGE_ID, receptionist);

        assertThat(state.appliedAmount()).isEqualByComparingTo("250.00");
        assertThat(state.pendingBalance()).isEqualByComparingTo("750.00");
        assertThat(state.status()).isEqualTo(ChargeStatus.PARCIAL);
    }

    @Test
    void con_abono_total_es_pagado() {
        when(movementRepository.sumValidAppliedAmount(CHARGE_ID))
                .thenReturn(Optional.of(new BigDecimal("1000.00")));

        charge("1000.00");
        assertThat(service.stateOf(CHARGE_ID, receptionist).status()).isEqualTo(ChargeStatus.PAGADO);
    }

    @Test
    void cargo_anulado_es_anulado() {
        Charge charge = charge("1000.00");
        charge.cancel(1L, "Duplicado", NOW);
        when(movementRepository.sumValidAppliedAmount(CHARGE_ID))
                .thenReturn(Optional.empty());

        assertThat(service.stateOf(CHARGE_ID, receptionist).status()).isEqualTo(ChargeStatus.ANULADO);
    }

    @Test
    void la_consulta_aplica_autorizacion_de_visibilidad() {
        Charge charge = charge("1000.00");
        when(chargeRepository.findById(CHARGE_ID)).thenReturn(Optional.of(charge));
        when(movementRepository.sumValidAppliedAmount(CHARGE_ID))
                .thenReturn(Optional.empty());

        service.stateOf(CHARGE_ID, receptionist);

        verify(authorization).requireView(receptionist, charge);
    }

    @Test
    void cargo_inexistente_no_se_revela() {
        when(chargeRepository.findById(CHARGE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.stateOf(CHARGE_ID, receptionist))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(authorization, movementRepository);
    }

    @Test
    void visibilidad_denegada_se_propaga() {
        Charge charge = charge("1000.00");
        when(chargeRepository.findById(CHARGE_ID)).thenReturn(Optional.of(charge));
        org.mockito.Mockito.doThrow(new ForbiddenOperationException("denegado"))
                .when(authorization).requireView(receptionist, charge);

        assertThatThrownBy(() -> service.stateOf(CHARGE_ID, receptionist))
                .isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(movementRepository);
    }

    @Test
    void ownership_denegado_no_consulta_movimientos() {
        Charge charge = charge("1000.00");
        AuthenticatedUser dentist = new AuthenticatedUser(10L, Role.DENTIST);
        org.mockito.Mockito.doThrow(new NotFoundException())
                .when(authorization).requireView(dentist, charge);

        assertThatThrownBy(() -> service.stateOf(CHARGE_ID, dentist))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(movementRepository);
    }

    @Test
    void no_hay_entrada_publica_para_calcular_estado_sin_actor() {
        assertThat(java.util.Arrays.stream(ChargeFinancialStateService.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers())))
                .allSatisfy(method -> assertThat(method.getParameterTypes())
                        .contains(AuthenticatedUser.class));
    }

    @Test
    void consulta_rechaza_sobrepago_persistido() {
        charge("1000.00");
        when(movementRepository.sumValidAppliedAmount(CHARGE_ID))
                .thenReturn(Optional.of(new BigDecimal("1000.01")));

        assertThatThrownBy(() -> service.stateOf(CHARGE_ID, receptionist))
                .isInstanceOf(com.dentalclinic.financial.domain.InvalidChargeException.class);
    }
}

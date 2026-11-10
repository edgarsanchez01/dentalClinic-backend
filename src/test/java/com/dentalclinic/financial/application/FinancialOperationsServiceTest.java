package com.dentalclinic.financial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.dentalclinic.appointment.domain.HttpIdempotency;
import com.dentalclinic.appointment.infrastructure.repository.HttpIdempotencyRepository;
import com.dentalclinic.catalog.application.ServiceCatalogService;
import com.dentalclinic.clinic.application.DentistService;
import com.dentalclinic.financial.application.command.CancelChargeCommand;
import com.dentalclinic.financial.application.command.ChargeItemData;
import com.dentalclinic.financial.application.command.ConfirmChargeCommand;
import com.dentalclinic.financial.application.command.RegisterPaymentCommand;
import com.dentalclinic.financial.application.command.ReversePaymentCommand;
import com.dentalclinic.financial.domain.Charge;
import com.dentalclinic.financial.domain.FinancialConflictException;
import com.dentalclinic.financial.domain.FinancialMovement;
import com.dentalclinic.financial.domain.InvalidFinancialMovementException;
import com.dentalclinic.financial.domain.PaymentMethod;
import com.dentalclinic.financial.infrastructure.repository.ChargeItemRepository;
import com.dentalclinic.financial.infrastructure.repository.ChargeRepository;
import com.dentalclinic.financial.infrastructure.repository.FinancialMovementRepository;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.fasterxml.jackson.databind.ObjectMapper;

class FinancialOperationsServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
    private final AuthenticatedUser actor = new AuthenticatedUser(1L, Role.OWNER);
    private ChargeRepository charges;
    private ChargeItemRepository items;
    private FinancialMovementRepository movements;
    private HttpIdempotencyRepository idempotency;
    private FinancialAuthorizationService authorization;
    private ChargeReferenceValidator references;
    private EntityManager em;
    private FinancialOperationsService service;
    private Charge charge;

    @BeforeEach
    void setup() {
        charges = mock(ChargeRepository.class);
        items = mock(ChargeItemRepository.class);
        movements = mock(FinancialMovementRepository.class);
        idempotency = mock(HttpIdempotencyRepository.class);
        authorization = mock(FinancialAuthorizationService.class);
        references = mock(ChargeReferenceValidator.class);
        em = mock(EntityManager.class);
        Query lock = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(lock);
        when(lock.setParameter(anyString(), any())).thenReturn(lock);
        service = new FinancialOperationsService(new ManualFinancialExecutionParticipant(charges, authorization,
                new FinancialTransactionParticipant(charges, items, movements, references,
                        mock(DentistService.class), mock(ServiceCatalogService.class)),
                new FinancialIdempotencyStore(idempotency, em,
                        new ObjectMapper().findAndRegisterModules()),
                mock(com.dentalclinic.appointment.application.AppointmentCheckoutReferenceReader.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new FinancialIntentExecutionGuard(mock(com.dentalclinic.financial.infrastructure.repository.FinancialIntentRepository.class))));
        charge = Charge.createConfirmed(2L, null, new BigDecimal("100"), null, actor.userId(), NOW);
        ReflectionTestUtils.setField(charge, "id", 10L);
        when(charges.findByIdForUpdate(10L)).thenReturn(Optional.of(charge));
        when(charges.save(any())).thenAnswer(invocation -> {
            Charge saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 11L);
            return saved;
        });
        when(movements.save(any())).thenAnswer(invocation -> {
            FinancialMovement saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 20L);
            return saved;
        });
        when(movements.sumValidAppliedAmount(anyLong())).thenReturn(Optional.empty());
    }

    @Test
    void calculaTotalExactoEnServidorYAutorizaAntesDeGuardar() {
        ConfirmChargeCommand command = new ConfirmChargeCommand(2L, null,
                List.of(new ChargeItemData(null, null, "A", 3, new BigDecimal("0.10")),
                        new ChargeItemData(null, null, "B", 1, new BigDecimal("0.20"))), null, "key");

        Charge result = service.confirmCharge(command, actor);

        assertThat(result.getTotalAmount()).isEqualByComparingTo("0.50");
        var order = org.mockito.Mockito.inOrder(authorization, references, charges);
        order.verify(authorization).requireCreateCharge(actor, command);
        order.verify(references).requireValidReferences(2L, null, actor);
        order.verify(charges).save(any());
        ArgumentCaptor<HttpIdempotency> stored = ArgumentCaptor.forClass(HttpIdempotency.class);
        verify(idempotency).save(stored.capture());
        assertThat(stored.getValue().getRequestHash()).hasSize(64);
        assertThat(stored.getValue().getOperation()).isEqualTo("FIN_CONFIRM_CHARGE");
    }

    @Test
    void pagoBloqueaYAutorizaAntesDeLeerSaldoYGuardar() {
        RegisterPaymentCommand command = new RegisterPaymentCommand(10L, PaymentMethod.CARD,
                new BigDecimal("40"), null, "key");

        service.registerPayment(command, actor);

        var order = org.mockito.Mockito.inOrder(charges, authorization, movements);
        order.verify(charges).findByIdForUpdate(10L);
        order.verify(authorization).requireRegisterPayment(actor, charge);
        order.verify(movements).sumValidAppliedAmount(10L);
        order.verify(movements).save(any());
    }

    @Test
    void rechazaSobrepagoSinEscribirMovimientosNiConsumirClave() {
        when(movements.sumValidAppliedAmount(10L)).thenReturn(Optional.of(new BigDecimal("70")));
        assertThatThrownBy(() -> service.registerPayment(new RegisterPaymentCommand(10L,
                PaymentMethod.CASH, new BigDecimal("30.01"), null, "key"), actor))
                .isInstanceOf(FinancialConflictException.class);
        org.mockito.Mockito.verify(movements, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verify(idempotency, org.mockito.Mockito.never()).save(any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void lasCuatroOperacionesExigenClaveNoVacia(String key) {
        ConfirmChargeCommand confirm = new ConfirmChargeCommand(2L, null,
                List.of(new ChargeItemData(null, null, "A", 1, BigDecimal.TEN)), null, key);
        assertThatThrownBy(() -> service.confirmCharge(confirm, actor)).isInstanceOf(InvalidFinancialMovementException.class);
        assertThatThrownBy(() -> service.registerPayment(new RegisterPaymentCommand(10L, PaymentMethod.CASH,
                BigDecimal.TEN, null, key), actor)).isInstanceOf(InvalidFinancialMovementException.class);
        assertThatThrownBy(() -> service.reversePayment(new ReversePaymentCommand(10L, 20L, "Error", key), actor))
                .isInstanceOf(InvalidFinancialMovementException.class);
        assertThatThrownBy(() -> service.cancelCharge(new CancelChargeCommand(10L, "Error", key), actor))
                .isInstanceOf(InvalidFinancialMovementException.class);
        verifyNoInteractions(charges, items, movements, idempotency, em);
    }

    @Test
    void identidadAusenteNoIniciaEscrituras() {
        assertThatThrownBy(() -> service.registerPayment(new RegisterPaymentCommand(10L,
                PaymentMethod.CASH, BigDecimal.ONE, null, "key"), null)).isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(charges, items, movements, idempotency, em);
    }

    @Test
    void noHayMetodosPublicosDeEscrituraSinActorOTransaccion() {
        assertThat(java.util.Arrays.stream(FinancialOperationsService.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers())))
                .hasSize(4).allSatisfy(method -> assertThat(method.getParameterTypes()).contains(AuthenticatedUser.class));
        var transaction = FinancialOperationsService.class.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(transaction.propagation()).isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
        assertThat(transaction.isolation()).isEqualTo(org.springframework.transaction.annotation.Isolation.READ_COMMITTED);
    }
}

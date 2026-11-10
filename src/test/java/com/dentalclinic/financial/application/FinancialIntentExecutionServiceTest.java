package com.dentalclinic.financial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.financial.application.command.CancelChargeCommand;
import com.dentalclinic.financial.domain.FinancialIntent;
import com.dentalclinic.financial.domain.FinancialIntentClosedException;
import com.dentalclinic.financial.domain.FinancialIntentCommandConflictException;
import com.dentalclinic.financial.domain.FinancialIntentDisposition;
import com.dentalclinic.financial.domain.FinancialIntentOperation;
import com.dentalclinic.financial.domain.InvalidFinancialMovementException;
import com.dentalclinic.financial.infrastructure.repository.FinancialIntentRepository;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

class FinancialIntentExecutionServiceTest {
    private final FinancialIdempotencyStore idempotency = mock(FinancialIdempotencyStore.class);
    private final FinancialIntentRepository repository = mock(FinancialIntentRepository.class);
    private final FinancialIntentExecutionGuard guard = new FinancialIntentExecutionGuard(repository);
    private final ManualFinancialExecutionParticipant participant = mock(ManualFinancialExecutionParticipant.class);
    private final FinancialAuthorizationService authorization = new FinancialAuthorizationService(null, null, null);
    private final FinancialIntentExecutionTransaction service =
            new FinancialIntentExecutionTransaction(idempotency, guard, authorization, participant, repository);
    private final FinancialIntentExecutionAttempt attempt = mock(FinancialIntentExecutionAttempt.class);
    private final AuthenticatedUser owner = new AuthenticatedUser(1L, Role.OWNER);
    private final String key = UUID.randomUUID().toString();
    private static final FinancialIntentOperation OP = FinancialIntentOperation.FIN_CANCEL_CHARGE;

    @Test
    void identidadSeBloqueaAntesDeLeerComandoYNuncaEncadenaFachadas() {
        var command = new CancelChargeCommand(10L, "motivo", key);
        when(repository.findByIdentityForUpdate(1L, OP, key)).thenReturn(Optional.of(intent(FinancialIntentDisposition.OPEN, 1)));
        when(idempotency.readCommand("{}", CancelChargeCommand.class)).thenReturn(command);
        when(idempotency.requestHash(command)).thenReturn("a".repeat(64));
        service.cancelCharge(key, owner, attempt);
        var order = inOrder(idempotency, repository, participant);
        order.verify(idempotency).lockIdentity(owner, FinancialIdempotencyStore.FINANCIAL_NAMESPACE, OP.name(), key);
        order.verify(repository).findByIdentityForUpdate(1L, OP, key);
        order.verify(participant).replayRegistered(any(), org.mockito.ArgumentMatchers.eq(owner), org.mockito.ArgumentMatchers.eq(com.dentalclinic.financial.domain.Charge.class));
        order.verify(idempotency).readCommand("{}", CancelChargeCommand.class);
        order.verify(idempotency).requestHash(command);
        order.verify(participant).cancelCharge(command, owner);
    }

    @ParameterizedTest
    @EnumSource(value = FinancialIntentDisposition.class, names = {"REJECTED", "ABANDONED"})
    void cierreImpideDeserializarYEjecutar(FinancialIntentDisposition state) {
        when(repository.findByIdentityForUpdate(1L, OP, key)).thenReturn(Optional.of(intent(state, 1)));
        assertThatThrownBy(() -> service.cancelCharge(key, owner, attempt)).isInstanceOf(FinancialIntentClosedException.class);
        verifyNoInteractions(participant);
    }

    @Test
    void versionDesconocidaNoSeInterpretaNiEjecuta() {
        when(repository.findByIdentityForUpdate(1L, OP, key)).thenReturn(Optional.of(intent(FinancialIntentDisposition.OPEN, 3)));
        assertThatThrownBy(() -> service.cancelCharge(key, owner, attempt)).isInstanceOf(FinancialIntentCommandConflictException.class);
        verifyNoInteractions(participant);
    }

    @Test
    void hashOClaveDelComandoNoPuedenCambiarIdentidad() {
        when(repository.findByIdentityForUpdate(1L, OP, key)).thenReturn(Optional.of(intent(FinancialIntentDisposition.OPEN, 2)));
        when(idempotency.readCommand("{}", CancelChargeCommand.class)).thenReturn(new CancelChargeCommand(10L, "motivo", key));
        when(idempotency.requestHash(any())).thenReturn("b".repeat(64));
        assertThatThrownBy(() -> service.cancelCharge(key, owner, attempt)).isInstanceOf(FinancialIntentCommandConflictException.class);
        when(idempotency.readCommand("{}", CancelChargeCommand.class)).thenReturn(new CancelChargeCommand(10L, "motivo", UUID.randomUUID().toString()));
        assertThatThrownBy(() -> service.cancelCharge(key, owner, attempt)).isInstanceOf(FinancialIntentCommandConflictException.class);
        org.mockito.Mockito.verify(participant, org.mockito.Mockito.never()).cancelCharge(any(), any());
    }

    @Test
    void identidadAjenaOAusenteNoEjecuta() {
        when(repository.findByIdentityForUpdate(1L, OP, key)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancelCharge(key, owner, attempt)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(participant);
    }

    @Test
    void rolInvalidoOClaveNoCanonicaNoConsultaRegistros() {
        assertThatThrownBy(() -> service.cancelCharge(key, null, attempt)).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.cancelCharge(key, new AuthenticatedUser(2L, Role.RECEPTIONIST), attempt))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.cancelCharge("legacy", owner, attempt)).isInstanceOf(InvalidFinancialMovementException.class);
        verifyNoInteractions(idempotency, repository, participant);
    }

    @Test
    void fachadaYParticipantesMantienenFronterasAtomicas() {
        assertThat(FinancialIntentExecutionService.class.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
        var tx = FinancialIntentExecutionTransaction.class.getAnnotation(Transactional.class);
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(tx.isolation()).isEqualTo(Isolation.READ_COMMITTED);
        assertThat(ManualFinancialExecutionParticipant.class.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(FinancialIntentExecutionGuard.class.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(java.util.Arrays.stream(FinancialIntentExecutionService.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers())))
                .hasSize(4).allSatisfy(m -> assertThat(m.getParameterTypes()).containsExactly(String.class, AuthenticatedUser.class));
    }

    @Test
    void legacySinIntencionNoExigeRegistroNiCambiaComandos() {
        when(repository.findByIdentityForUpdate(1L, OP, "legacy")).thenReturn(Optional.empty());
        guard.checkIfRegistered(owner, OP, "legacy", "hash");
        verifyNoInteractions(idempotency, participant);
    }

    private FinancialIntent intent(FinancialIntentDisposition disposition, int version) {
        return new FinancialIntent(1L, OP, key, version, 1, "a".repeat(64), "{}", disposition,
                Instant.now(), null, null, null, null, null);
    }
}

package com.dentalclinic.financial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.dentalclinic.financial.application.command.CancelChargeCommand;
import com.dentalclinic.financial.application.command.ConfirmChargeCommand;
import com.dentalclinic.financial.application.command.RegisterPaymentCommand;
import com.dentalclinic.financial.application.command.ReversePaymentCommand;
import com.dentalclinic.financial.domain.FinancialIntent;
import com.dentalclinic.financial.domain.FinancialIntentCommandConflictException;
import com.dentalclinic.financial.domain.FinancialIntentDisposition;
import com.dentalclinic.financial.domain.FinancialIntentOperation;
import com.dentalclinic.financial.domain.InvalidFinancialMovementException;
import com.dentalclinic.financial.domain.PaymentMethod;
import com.dentalclinic.financial.infrastructure.repository.FinancialIntentRepository;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

class FinancialIntentRegistrationServiceTest {
    private final FinancialIntentRepository repository = mock(FinancialIntentRepository.class);
    private final FinancialIdempotencyStore idempotency = mock(FinancialIdempotencyStore.class);
    private final FinancialAuthorizationService authorization = new FinancialAuthorizationService(null, null, null);
    private final FinancialIntentCommandCodec codec = mock(FinancialIntentCommandCodec.class);
    private final FinancialIntentRegistrationService service =
            new FinancialIntentRegistrationService(repository, idempotency, authorization, codec);
    private final AuthenticatedUser owner = new AuthenticatedUser(1L, Role.OWNER);
    private final String key = UUID.randomUUID().toString();

    @BeforeEach
    void codec() {
        when(idempotency.lockRequest(any(), anyString(), anyString(), anyString(), any())).thenReturn("a".repeat(64));
        when(codec.encode(any())).thenReturn("{}");
    }

    @ParameterizedTest
    @EnumSource(FinancialIntentOperation.class)
    void soloAdmiteLasCuatroOperacionesConSuIdentidadExistente(FinancialIntentOperation operation) {
        FinancialIntent expected = intent(operation, 1, 1, "a".repeat(64));
        when(repository.findByIdentityForUpdate(owner.userId(), operation, key)).thenReturn(Optional.of(expected));

        assertThat(register(operation, owner)).isSameAs(expected);
        verify(idempotency).lockRequest(eq(owner), eq(FinancialIdempotencyStore.FINANCIAL_NAMESPACE),
                eq(operation.name()), eq(key), any());
        verify(repository).insertOpen(eq(owner.userId()), eq(operation), eq(key), eq(2), eq(1),
                eq("a".repeat(64)), eq("{}"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-a-uuid", "1-1-1-1-1", "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"})
    void noNormalizaNiAdmiteIdentidadesNoCanonicas(String invalid) {
        assertThatThrownBy(() -> service.register(new CancelChargeCommand(1L, "motivo", invalid), owner))
                .isInstanceOf(InvalidFinancialMovementException.class);
        verifyNoInteractions(repository, idempotency);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"DENTIST", "PATIENT"})
    void rolSinEscrituraNoPuedeRegistrarNiInspeccionarIdentidades(Role role) {
        for (FinancialIntentOperation operation : FinancialIntentOperation.values()) {
            assertThatThrownBy(() -> register(operation, new AuthenticatedUser(2L, role)))
                    .isInstanceOf(ForbiddenOperationException.class);
        }
        verifyNoInteractions(repository, idempotency);
    }

    @Test
    void recepcionNoRegistraReversaNiAnulacion() {
        for (var operation : List.of(FinancialIntentOperation.FIN_REVERSE_PAYMENT,
                FinancialIntentOperation.FIN_CANCEL_CHARGE)) {
            assertThatThrownBy(() -> register(operation, new AuthenticatedUser(2L, Role.RECEPTIONIST)))
                    .isInstanceOf(ForbiddenOperationException.class);
        }
        verifyNoInteractions(repository, idempotency);
    }

    @Test
    void exigeActorAutenticadoAntesDeConsultarIdentidad() {
        for (AuthenticatedUser actor : java.util.Arrays.asList(null, new AuthenticatedUser(null, Role.OWNER),
                new AuthenticatedUser(0L, Role.OWNER), new AuthenticatedUser(1L, null))) {
            assertThatThrownBy(() -> register(FinancialIntentOperation.FIN_CANCEL_CHARGE, actor))
                    .isInstanceOf(ForbiddenOperationException.class);
        }
        verifyNoInteractions(repository, idempotency);
    }

    @Test
    void noReemplazaComandoSiHashOVersionDifieren() {
        var operation = FinancialIntentOperation.FIN_CANCEL_CHARGE;
        for (FinancialIntent existing : List.of(intent(operation, 1, 1, "b".repeat(64)),
                intent(operation, 3, 1, "a".repeat(64)), intent(operation, 1, 2, "a".repeat(64)))) {
            when(repository.findByIdentityForUpdate(1L, operation, key)).thenReturn(Optional.of(existing));
            assertThatThrownBy(() -> register(operation, owner))
                    .isInstanceOf(FinancialIntentCommandConflictException.class);
        }
    }

    @Test
    void reciboLegacyIncompatibleImpideInsertarIntencion() {
        when(idempotency.hasConflictingReceipt(eq(owner), anyString(), eq(key), anyString())).thenReturn(true);
        assertThatThrownBy(() -> register(FinancialIntentOperation.FIN_CANCEL_CHARGE, owner))
                .isInstanceOf(FinancialIntentCommandConflictException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void modeloYRepositorioNoExponenMutadoresBorradoNiEjecucion() {
        assertThat(FinancialIntent.class.isRecord()).isTrue();
        assertThat(FinancialIntentDisposition.values()).containsExactly(
                FinancialIntentDisposition.OPEN, FinancialIntentDisposition.REJECTED, FinancialIntentDisposition.ABANDONED);
        assertThat(java.util.Arrays.stream(FinancialIntentRepository.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName))
                .doesNotContain("save", "update", "delete", "deleteAll", "execute", "abandon", "reject");
        assertThat(intent(FinancialIntentOperation.FIN_CANCEL_CHARGE, 1, 1, "secret-hash").toString())
                .doesNotContain("secret", key);
    }

    private FinancialIntent register(FinancialIntentOperation operation, AuthenticatedUser actor) {
        return switch (operation) {
            case FIN_CONFIRM_CHARGE -> service.register(new ConfirmChargeCommand(1L, null, List.of(), "nota", key), actor);
            case FIN_REGISTER_PAYMENT -> service.register(new RegisterPaymentCommand(1L, PaymentMethod.CARD,
                    new BigDecimal("12.3400"), "nota", key), actor);
            case FIN_REVERSE_PAYMENT -> service.register(new ReversePaymentCommand(1L, 2L, "motivo", key), actor);
            case FIN_CANCEL_CHARGE -> service.register(new CancelChargeCommand(1L, "motivo", key), actor);
        };
    }

    private FinancialIntent intent(FinancialIntentOperation operation, int commandVersion, int hashVersion, String hash) {
        return new FinancialIntent(1L, operation, key, commandVersion, hashVersion, hash, "secret-command",
                FinancialIntentDisposition.OPEN, Instant.now(), null, null, null, null, null);
    }
}

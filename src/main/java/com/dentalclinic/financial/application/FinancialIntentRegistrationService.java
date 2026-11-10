package com.dentalclinic.financial.application;

import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.financial.application.command.CancelChargeCommand;
import com.dentalclinic.financial.application.command.ConfirmChargeCommand;
import com.dentalclinic.financial.application.command.RegisterPaymentCommand;
import com.dentalclinic.financial.application.command.ReversePaymentCommand;
import com.dentalclinic.financial.domain.FinancialIntent;
import com.dentalclinic.financial.domain.FinancialIntentCommandConflictException;
import com.dentalclinic.financial.domain.FinancialIntentOperation;
import com.dentalclinic.financial.domain.InvalidFinancialMovementException;
import com.dentalclinic.financial.infrastructure.repository.FinancialIntentRepository;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/**
 * B1 exclusivamente: registro interno durable, sin ejecutar ni cerrar intenciones.
 * El actor proviene del contexto autenticado del llamador, nunca del comando.
 * Gate por rol antes de consultar identidad. Las referencias/reglas de negocio y
 * su reautorizacion al ejecutar corresponden a B2, no son prueba de efecto aqui.
 * Orden: advisory lock existente -> fila de intencion. No bloquea citas/cargos.
 */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
public class FinancialIntentRegistrationService {
    static final int COMMAND_VERSION = FinancialIntentCommandCodec.CURRENT_VERSION;
    static final int HASH_VERSION = 1;

    private final FinancialIntentRepository intents;
    private final FinancialIdempotencyStore idempotency;
    private final FinancialAuthorizationService authorization;
    private final FinancialIntentCommandCodec codec;

    FinancialIntentRegistrationService(FinancialIntentRepository intents,
            FinancialIdempotencyStore idempotency, FinancialAuthorizationService authorization,
            FinancialIntentCommandCodec codec) {
        this.intents = intents;
        this.idempotency = idempotency;
        this.authorization = authorization;
        this.codec = codec;
    }

    public FinancialIntent register(ConfirmChargeCommand command, AuthenticatedUser actor) {
        return registerWithOutcome(command, actor).intent();
    }

    public Registration registerWithOutcome(ConfirmChargeCommand command, AuthenticatedUser actor) {
        Objects.requireNonNull(command, "command es obligatorio");
        return register(actor, FinancialIntentOperation.FIN_CONFIRM_CHARGE, command.idempotencyKey(), command);
    }

    public FinancialIntent register(RegisterPaymentCommand command, AuthenticatedUser actor) {
        return registerWithOutcome(command, actor).intent();
    }

    public Registration registerWithOutcome(RegisterPaymentCommand command, AuthenticatedUser actor) {
        Objects.requireNonNull(command, "command es obligatorio");
        return register(actor, FinancialIntentOperation.FIN_REGISTER_PAYMENT, command.idempotencyKey(), command);
    }

    public FinancialIntent register(ReversePaymentCommand command, AuthenticatedUser actor) {
        return registerWithOutcome(command, actor).intent();
    }

    public Registration registerWithOutcome(ReversePaymentCommand command, AuthenticatedUser actor) {
        Objects.requireNonNull(command, "command es obligatorio");
        return register(actor, FinancialIntentOperation.FIN_REVERSE_PAYMENT, command.idempotencyKey(), command);
    }

    public FinancialIntent register(CancelChargeCommand command, AuthenticatedUser actor) {
        return registerWithOutcome(command, actor).intent();
    }

    public Registration registerWithOutcome(CancelChargeCommand command, AuthenticatedUser actor) {
        Objects.requireNonNull(command, "command es obligatorio");
        return register(actor, FinancialIntentOperation.FIN_CANCEL_CHARGE, command.idempotencyKey(), command);
    }

    private Registration register(AuthenticatedUser actor, FinancialIntentOperation operation,
            String key, Object command) {
        authorization.requireIntentRegistration(actor, operation);
        requireCanonicalKey(key);
        String hash = idempotency.lockRequest(actor, FinancialIdempotencyStore.FINANCIAL_NAMESPACE,
                operation.name(), key, command);
        if (idempotency.hasConflictingReceipt(actor, operation.name(), key, hash)) {
            throw new FinancialIntentCommandConflictException();
        }
        boolean created = intents.insertOpen(actor.userId(), operation, key, COMMAND_VERSION, HASH_VERSION,
                hash, codec.encode(command));
        FinancialIntent intent = intents.findByIdentityForUpdate(actor.userId(), operation, key)
                .orElseThrow(() -> new IllegalStateException("No se pudo recuperar la intencion registrada."));
        if (!FinancialIntentCommandCodec.supports(intent.commandVersion()) || intent.hashVersion() != HASH_VERSION
                || !intent.requestHash().equals(hash)) {
            throw new FinancialIntentCommandConflictException();
        }
        // Repetir registro conserva exactamente el comando y su disposicion (incluso cerrada).
        return new Registration(intent, created);
    }

    /** El resultado de INSERT decide 201/200 bajo el lock; nunca inferirlo desde timestamps. */
    public record Registration(FinancialIntent intent, boolean created) { }

    static void requireCanonicalKey(String key) {
        try {
            if (key == null || !UUID.fromString(key).toString().equals(key)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new InvalidFinancialMovementException("La identidad de intencion debe ser un UUID canonico.");
        }
    }
}

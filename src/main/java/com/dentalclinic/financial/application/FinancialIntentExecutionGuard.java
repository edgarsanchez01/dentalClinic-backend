package com.dentalclinic.financial.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.financial.domain.FinancialIntent;
import com.dentalclinic.financial.domain.FinancialIntentClosedException;
import com.dentalclinic.financial.domain.FinancialIntentCommandConflictException;
import com.dentalclinic.financial.domain.FinancialIntentDisposition;
import com.dentalclinic.financial.domain.FinancialIntentOperation;
import com.dentalclinic.financial.infrastructure.repository.FinancialIntentRepository;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;

/** Solo se invoca DESPUES del advisory lock de identidad y ANTES de locks de negocio. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class FinancialIntentExecutionGuard {
    private final FinancialIntentRepository intents;

    FinancialIntentExecutionGuard(FinancialIntentRepository intents) {
        this.intents = intents;
    }

    public FinancialIntent requireRegistered(AuthenticatedUser actor, FinancialIntentOperation operation, String key) {
        return intents.findByIdentityForUpdate(actor.userId(), operation, key).orElseThrow(NotFoundException::new);
    }

    /** Legacy no exige registro ni lo crea; una identidad registrada nunca se puede reemplazar/reabrir. */
    public void checkIfRegistered(AuthenticatedUser actor, FinancialIntentOperation operation, String key, String hash) {
        intents.findByIdentityForUpdate(actor.userId(), operation, key).ifPresent(intent -> {
            requireCompatible(intent, hash);
            requireOpen(intent);
        });
    }

    static void requireCompatible(FinancialIntent intent, String hash) {
        requireSupportedVersion(intent);
        if (!intent.requestHash().equals(hash)) {
            throw new FinancialIntentCommandConflictException();
        }
    }

    static void requireSupportedVersion(FinancialIntent intent) {
        if (!FinancialIntentCommandCodec.supports(intent.commandVersion())
                || intent.hashVersion() != FinancialIntentRegistrationService.HASH_VERSION) {
            throw new FinancialIntentCommandConflictException();
        }
    }

    static void requireOpen(FinancialIntent intent) {
        if (intent.disposition() != FinancialIntentDisposition.OPEN) {
            throw new FinancialIntentClosedException();
        }
    }
}

package com.dentalclinic.identity.infrastructure.lock;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

/**
 * Exclusión mutua del onboarding mediante {@code pg_advisory_xact_lock} (Architecture.md §15.5).
 *
 * <p>Sock: el lock es de transacción, se libera automáticamente al hacer commit o rollback, de modo
 * que no puede quedar tomado por un proceso muerto. La clave es un valor fijo y documentado: solo
 * esta aplicación usa el lock, y cualquier otro lock del mismo número en la misma base solo
 * bloquearía un onboarding simultáneo, que es exactamente lo buscado.
 *
 * <p>Se exige una transacción activa ({@link Propagation#MANDATORY}): tomar el lock fuera de la
 * transacción que crea la clínica no tendría sentido, porque se liberaría de inmediato.
 */
@Component
public class InstallationLock {

    /** Clave arbitraria pero estable, derivada del nombre del dominio para evitar colisiones. */
    private static final long INSTALLATION_LOCK_KEY = 0x0DE17A15C11C0001L;

    private final EntityManager entityManager;

    public InstallationLock(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Bloquea hasta obtener el lock de instalación; se libera al terminar la transacción. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire() {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:key)")
                .setParameter("key", INSTALLATION_LOCK_KEY)
                .getSingleResult();
    }
}
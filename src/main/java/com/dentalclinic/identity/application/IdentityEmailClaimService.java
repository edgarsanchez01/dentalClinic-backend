package com.dentalclinic.identity.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.identity.domain.IdentityEmailOutbox;
import com.dentalclinic.identity.domain.IdentityEmailStatus;
import com.dentalclinic.identity.infrastructure.repository.IdentityEmailOutboxRepository;

/**
 * Persistencia transaccional del claim y del resultado de entrega (F8B.2).
 *
 * <p>Cada método abre una <strong>transacción corta</strong>. {@link #claim} bloquea las filas
 * elegibles con {@code FOR UPDATE SKIP LOCKED} y las reserva con un lease; la transacción termina
 * <strong>antes</strong> de que el dispatcher abra SMTP. Marcar SENT/retry/FAILED son operaciones
 * separadas y pequeñas, nunca envueltas alrededor del envío de red (Architecture.md 9.1/9.4).
 *
 * <p>Resiliencia: el lease vive en {@code lease_until}; si el proceso muere entre el claim y el
 * marcado, la fila vuelve a ser elegible al vencer el lease y no se pierde. Los resultados verifican
 * la reserva ({@code attempts} + {@code lease_until}) para que un worker obsoleto no pise a uno
 * posterior.
 */
@Service
public class IdentityEmailClaimService {

    private final IdentityEmailOutboxRepository repository;
    private final Clock clock;

    public IdentityEmailClaimService(IdentityEmailOutboxRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Reclama hasta {@code batchSize} filas elegibles y las reserva con un lease. */
    @Transactional
    public List<ClaimedIdentityEmail> claim(Instant now, int batchSize, Duration lease) {
        List<Long> ids = repository.lockClaimable(now, batchSize);
        if (ids.isEmpty()) {
            return List.of();
        }
        // TIMESTAMPTZ tiene precisión de microsegundos: el sello debe sobrevivir al round-trip a BD.
        Instant leaseUntil = now.plus(lease).truncatedTo(ChronoUnit.MICROS);
        List<ClaimedIdentityEmail> claimed = new ArrayList<>(ids.size());
        for (IdentityEmailOutbox row : repository.findAllById(ids)) {
            if (row.getStatus() != IdentityEmailStatus.PENDING || row.getSupersededAt() != null) {
                continue;
            }
            row.markClaimStarted(leaseUntil, now);
            claimed.add(snapshot(row, leaseUntil));
        }
        return claimed;
    }

    @Transactional
    public void markSent(ClaimedIdentityEmail email, Instant now) {
        requireOwned(email).recordSent(now);
    }

    @Transactional
    public void scheduleRetry(ClaimedIdentityEmail email, String error, Instant nextAttemptAt,
            Instant now) {
        requireOwned(email).scheduleRetry(error, nextAttemptAt, now);
    }

    @Transactional
    public void markFailed(ClaimedIdentityEmail email, String error, Instant now) {
        requireOwned(email).markFailed(error, now);
    }

    /** Invalida una entrega vigente (p. ej. emisión obsoleta detectada por el compositor). */
    @Transactional
    public void supersede(ClaimedIdentityEmail email, Instant now) {
        repository.findLockedById(email.id())
                .filter(row -> row.ownsReservation(email.attempts(), email.leaseUntil()))
                // Lease vencido no significa emisión obsoleta: conservar para recuperación.
                .filter(row -> row.getLeaseUntil() != null && row.getLeaseUntil().isAfter(now))
                .ifPresent(row -> row.markSuperseded(now));
    }

    /**
     * Bloquea la fila y comprueba que sigue siendo la reserva de este intento. Rechaza el resultado
     * si otro worker ya la cerró o renovó, sin tocar la fila.
     */
    private IdentityEmailOutbox requireOwned(ClaimedIdentityEmail email) {
        IdentityEmailOutbox row = repository.findLockedById(email.id()).orElseThrow(
                () -> new IllegalStateException("Entrega de identidad reclamada inexistente: " + email.id()));
        if (row.getStatus() != IdentityEmailStatus.PENDING || row.getSupersededAt() != null
                || !row.ownsReservation(email.attempts(), email.leaseUntil())) {
            throw new IllegalStateException(
                    "El resultado no corresponde a la reserva vigente de la entrega: " + email.id());
        }
        return row;
    }

    private static ClaimedIdentityEmail snapshot(IdentityEmailOutbox row, Instant leaseUntil) {
        return new ClaimedIdentityEmail(row.getId(), row.getUserId(), row.getPurpose(),
                row.getRecipientEmail(), row.getTokenNonce(), row.getTokenHash(), row.getAttempts(),
                leaseUntil);
    }
}

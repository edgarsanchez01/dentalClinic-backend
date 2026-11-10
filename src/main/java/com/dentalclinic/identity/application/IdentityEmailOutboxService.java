package com.dentalclinic.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.identity.domain.IdentityEmailOutbox;
import com.dentalclinic.identity.domain.IdentityEmailPurpose;
import com.dentalclinic.identity.infrastructure.repository.IdentityEmailOutboxRepository;

/**
 * Encolado transaccional de enlaces de identidad (F8B.2, D2).
 *
 * <p>Es la puerta de <strong>productor</strong> de la outbox. Desde 8B.3 la invocan
 * {@code StaffInvitationService} y {@code PasswordService} mediante {@code IdentityLinkIssuer}.
 * El productor debe, en la MISMA transacción de negocio, emitir el
 * token con {@code IdentityLinkTokens}, persistir solo su SHA-256 en {@code invitations} /
 * {@code password_reset_tokens} y llamar a {@link #enqueue} con el nonce y el hash.
 *
 * <p><strong>Idempotencia de encolado.</strong> Una misma {@code idempotencyKey} devuelve siempre la
 * misma fila. La clave es única en base (V018); ante dos encolados concurrentes con la misma clave,
 * uno gana y el otro falla por constraint (el productor debe tratar el duplicado como no-op).
 *
 * <p><strong>Invalidación de entregas anteriores.</strong> Reemitir para la misma cuenta y propósito
 * marca {@code superseded_at} en la entrega vigente anterior, de modo que nunca hay dos entregas
 * activas y la vieja no vuelve a enviarse.
 */
@Service
public class IdentityEmailOutboxService {

    private final IdentityEmailOutboxRepository repository;
    private final Clock clock;

    public IdentityEmailOutboxService(IdentityEmailOutboxRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Encola una emisión ya producida. Si la {@code idempotencyKey} ya existe, devuelve la fila
     * existente sin reenviar ni reemitir.
     */
    @Transactional
    public IdentityEmailOutbox enqueue(Enqueue command) {
        Objects.requireNonNull(command, "command es obligatorio");
        Optional<IdentityEmailOutbox> existing = repository.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant now = clock.instant();
        repository.supersedeActive(command.userId(), command.purpose(), now);
        IdentityEmailOutbox row = IdentityEmailOutbox.enqueue(command.userId(), command.purpose(),
                command.recipientEmail(), command.tokenNonce(), command.tokenHash(),
                command.idempotencyKey(), now);
        return repository.saveAndFlush(row);
    }

    /**
     * Datos de una emisión a encolar. {@code tokenNonce} y {@code tokenHash} los produce el emisor
     * con {@code IdentityLinkTokens}: aquí no se reemite ningún token.
     */
    public record Enqueue(Long userId, IdentityEmailPurpose purpose, String recipientEmail,
            String tokenNonce, String tokenHash, String idempotencyKey) {
        @Override
        public String toString() {
            return "Enqueue{userId=" + userId + ", purpose=" + purpose + "}";
        }
    }
}

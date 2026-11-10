package com.dentalclinic.identity.application;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.identity.application.IdentityEmailOutboxService.Enqueue;
import com.dentalclinic.identity.domain.IdentityEmailPurpose;
import com.dentalclinic.identity.domain.Invitation;
import com.dentalclinic.identity.domain.PasswordResetToken;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.infrastructure.config.IdentityLinkProperties;
import com.dentalclinic.identity.infrastructure.repository.InvitationRepository;
import com.dentalclinic.identity.infrastructure.repository.PasswordResetTokenRepository;
import com.dentalclinic.identity.infrastructure.token.IdentityLinkTokens;
import com.dentalclinic.identity.infrastructure.token.TokenHasher;

/** Emisión + outbox en la transacción del productor. Sin SMTP; mantiene modo legacy con flags off. */
@Service
public class IdentityLinkIssuer {
    private final IdentityLinkTokens tokens;
    private final IdentityLinkProperties links;
    private final TokenHasher hasher;
    private final InvitationRepository invitations;
    private final PasswordResetTokenRepository resets;
    private final IdentityEmailOutboxService outbox;

    public IdentityLinkIssuer(IdentityLinkTokens tokens, IdentityLinkProperties links, TokenHasher hasher,
            InvitationRepository invitations, PasswordResetTokenRepository resets, IdentityEmailOutboxService outbox) {
        this.tokens = tokens;
        this.links = links;
        this.hasher = hasher;
        this.invitations = invitations;
        this.resets = resets;
        this.outbox = outbox;
    }

    /** El valor en claro solo se devuelve al servicio de invitaciones para su mecanismo dev existente. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String issue(User user, IdentityEmailPurpose purpose, Instant expiresAt) {
        String nonce = links.configured() ? tokens.newNonce() : null;
        String raw = nonce == null ? hasher.generateOpaqueToken()
                : tokens.token(purpose == IdentityEmailPurpose.STAFF_INVITATION
                        ? IdentityLinkTokens.Domain.STAFF_INVITATION : IdentityLinkTokens.Domain.PASSWORD_RESET,
                        user.getId(), nonce);
        String hash = hasher.hash(raw);
        if (purpose == IdentityEmailPurpose.STAFF_INVITATION) {
            invitations.saveAndFlush(Invitation.issueForExistingUser(hash, user.getEmail(), user.getRole(),
                    user.getId(), expiresAt));
        } else {
            resets.saveAndFlush(PasswordResetToken.issue(user.getId(), hash, expiresAt));
        }
        if (nonce != null) {
            outbox.enqueue(new Enqueue(user.getId(), purpose, user.getEmail(), nonce, hash,
                    "identity:" + purpose + ":" + hash));
        }
        return raw;
    }
}

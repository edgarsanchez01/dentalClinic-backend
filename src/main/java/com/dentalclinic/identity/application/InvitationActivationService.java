package com.dentalclinic.identity.application;

import java.time.Clock;
import java.time.Instant;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.identity.domain.EmailCanonicalizer;
import com.dentalclinic.identity.domain.Invitation;
import com.dentalclinic.identity.domain.PasswordPolicy;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.domain.exception.EmailAlreadyRegisteredException;
import com.dentalclinic.identity.domain.exception.InvalidTokenException;
import com.dentalclinic.identity.infrastructure.repository.InvitationRepository;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;
import com.dentalclinic.identity.infrastructure.token.TokenHasher;

/**
 * Consumo de invitaciones: activación de cuentas por token de un solo uso.
 *
 * <p>Implementa el lado del <strong>consumidor</strong> del contrato de invitación (RF-IA-09: de un
 * solo uso, expirable y hasheado; PRD §6.5.2; AD-20). El emisor —alta de dentistas y
 * recepcionistas por el OWNER— es Fase 3, y el alta administrativa de pacientes con invitación es
 * Fase 4. Aquí no hay ningún endpoint que cree invitaciones: solo el que las gasta.
 *
 * <h2>Por qué el consumo es atómico</h2>
 *
 * <p>Un token de un solo uso comprobado con un {@code SELECT} y luego marcado como usado admite
 * dos activaciones simultáneas del mismo enlace. El consumo es un único {@code UPDATE} condicional:
 * solo la primera petición afecta a una fila, y la segunda recibe 0 filas y se rechaza. Como la
 * cuenta se resuelve dentro de la misma transacción, la excepción revierte el alta completa y no
 * queda ningún usuario huérfano.
 */
@Service
public class InvitationActivationService {

    private final InvitationRepository invitationRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenHasher tokenHasher;
    private final Clock clock;

    public InvitationActivationService(InvitationRepository invitationRepository,
                                       UserRepository userRepository,
                                       PasswordEncoder passwordEncoder,
                                       TokenHasher tokenHasher,
                                       Clock clock) {
        this.invitationRepository = invitationRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenHasher = tokenHasher;
        this.clock = clock;
    }

    /**
     * Activa la cuenta a la que apunta el token y establece su contraseña.
     *
     * <p>Soporta los dos modos que describen los documentos: si la invitación ya viene enlazada a
     * una cuenta pendiente, la activa; si no, da de alta la cuenta con el email y el rol de la
     * invitación. El rol <strong>siempre</strong> sale de la invitación y nunca del cuerpo de la
     * petición, y nunca es OWNER (P-07 OPEN).
     *
     * @return el usuario activado, para que la capa HTTP pueda resolver el perfil
     * @throws InvalidTokenException si el token no existe, está caducado, ya se usó o no resulta
     *     utilizable. El mensaje es idéntico en todos los casos, para no revelar cuál fue
     */
    @Transactional
    public User activate(String rawToken, String rawPassword) {
        PasswordPolicy.requireWithinHashingLimit(rawPassword);
        Instant now = clock.instant();
        String tokenHash = tokenHasher.hash(rawToken);

        Invitation invitation = invitationRepository.findByTokenHash(tokenHash)
                .orElseThrow(InvalidTokenException::new);
        if (!invitation.isUsableAt(now)) {
            throw new InvalidTokenException();
        }

        String passwordHash = passwordEncoder.encode(rawPassword);
        User user = invitation.getUserId().isPresent()
                ? activatePending(invitation.getUserId().get(), passwordHash)
                : registerFromInvitation(invitation, passwordHash);

        if (invitationRepository.consumeAtomically(tokenHash, user.getId(), now) != 1) {
            // Otra petición ganó el consumo, o la invitación ya no es la vigente. Se relanza y la
            // transacción deshace el alta de la cuenta.
            throw new InvalidTokenException();
        }
        return user;
    }

    /**
     * Modo "personal" (PRD §6.5.2 opción A): la cuenta ya existe y está pendiente de activar.
     */
    private User activatePending(Long userId, String passwordHash) {
        User user = userRepository.findById(userId).orElseThrow(InvalidTokenException::new);
        if (user.isActive()) {
            // Ya activada: la invitación es redundante o los datos son incoherentes. Se responde
            // igual que ante un token caducado en lugar de revelar el estado de la cuenta.
            throw new InvalidTokenException();
        }
        user.changePasswordHash(passwordHash);
        user.activate();
        return user;
    }

    /**
     * Modo "paciente" (flujo de P-03): la cuenta se crea al activar, ya activa y sin verificación
     * de email, porque el producto no tiene verificación de email (P-03 CLOSED).
     *
     * <p>La comparación de unicidad la resuelve el constraint {@code UNIQUE} sobre el email
     * canónico, no solo esta comprobación previa (defense in depth, §8.3). La precomprobación
     * existe únicamente para poder devolver un 409 con un mensaje propio en lugar de dejar que la
     * violación del constraint llegue más tarde.
     */
    private User registerFromInvitation(Invitation invitation, String passwordHash) {
        String canonicalEmail = EmailCanonicalizer.canonicalize(invitation.getEmail());
        if (userRepository.existsByEmail(canonicalEmail)) {
            throw new EmailAlreadyRegisteredException();
        }
        return userRepository.saveAndFlush(User.create(canonicalEmail, passwordHash, invitation.getRole()));
    }
}
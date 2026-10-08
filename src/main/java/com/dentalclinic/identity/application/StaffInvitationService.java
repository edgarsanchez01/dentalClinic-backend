package com.dentalclinic.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.clinic.application.DentistService;
import com.dentalclinic.clinic.application.DentistService.DentistData;
import com.dentalclinic.clinic.application.DentistService.DentistProfile;
import com.dentalclinic.identity.domain.EmailCanonicalizer;
import com.dentalclinic.identity.domain.Invitation;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.domain.exception.EmailAlreadyRegisteredException;
import com.dentalclinic.identity.infrastructure.repository.InvitationRepository;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;
import com.dentalclinic.identity.infrastructure.security.SecurityProperties;
import com.dentalclinic.identity.infrastructure.token.TokenHasher;

/**
 * Invitación de personal por el OWNER (Fase 3, {@code /api/users}).
 *
 * <p>Crea una cuenta <strong>pendiente de activar</strong> ({@code active=false}) y emite una
 * invitación de un solo uso que se consume por {@code POST /api/auth/activate} (F2, RF-IA-09). El
 * rol <strong>nunca</strong> viene del cuerpo: lo fija el endpoint ({@code /api/users/dentists} →
 * {@code DENTIST}; {@code /api/users/receptionists} → {@code RECEPTIONIST}). No se crea un segundo
 * OWNER (P-07 OPEN) ni pacientes por este flujo (Fase 4).
 *
 * <p><strong>Entrega del token.</strong> En Fase 3 no existe canal de email (comunicaciones es
 * Fase 8). Siguiendo el precedente de {@code PasswordService.requestReset}, el token en claro
 * <strong>no sale de este servicio</strong>: se persiste solo su hash SHA-256, no se devuelve, no se
 * registra y los tests lo reconstruyen por repositorio. Es la razón por la que un token de
 * invitación no puede filtrarse por un log.
 */
@Service
public class StaffInvitationService {

    private final UserRepository userRepository;
    private final InvitationRepository invitationRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenHasher tokenHasher;
    private final SecurityProperties properties;
    private final DentistService dentistService;
    private final Clock clock;

    public StaffInvitationService(UserRepository userRepository,
                                  InvitationRepository invitationRepository,
                                  PasswordEncoder passwordEncoder,
                                  TokenHasher tokenHasher,
                                  SecurityProperties properties,
                                  DentistService dentistService,
                                  Clock clock) {
        this.userRepository = userRepository;
        this.invitationRepository = invitationRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenHasher = tokenHasher;
        this.properties = properties;
        this.dentistService = dentistService;
        this.clock = clock;
    }

    @Transactional
    public StaffMemberView inviteDentist(NewDentist command) {
        String email = EmailCanonicalizer.canonicalize(command.email());
        User user = createPendingUser(email, Role.DENTIST);
        dentistService.createForUser(user.getId(), new DentistData(command.firstName(),
                command.lastName(), command.phone(), command.specialty(), command.licenseNumber(),
                command.publicInfo(), command.photoUrl()));
        issueInvitation(user, Role.DENTIST);
        return viewOf(user, dentistService.profilesByUserIds(List.of(user.getId())).get(user.getId()));
    }

    @Transactional
    public StaffMemberView inviteReceptionist(NewReceptionist command) {
        String email = EmailCanonicalizer.canonicalize(command.email());
        User user = createPendingUser(email, Role.RECEPTIONIST);
        issueInvitation(user, Role.RECEPTIONIST);
        return viewOf(user, null);
    }

    @Transactional(readOnly = true)
    public List<StaffMemberView> listTeam() {
        List<User> users = userRepository.findAll(Sort.by(Sort.Direction.ASC, "createdAt"));
        Map<Long, DentistProfile> profiles =
                dentistService.profilesByUserIds(users.stream().map(User::getId).toList());
        return users.stream()
                .sorted(Comparator.comparing(User::getId))
                .map(user -> viewOf(user, profiles.get(user.getId())))
                .toList();
    }

    private User createPendingUser(String canonicalEmail, Role role) {
        if (userRepository.existsByEmail(canonicalEmail)) {
            throw new EmailAlreadyRegisteredException();
        }
        // Hash de un valor aleatorio e inutilizable: la cuenta no tiene contraseña utilizable hasta
        // que se activa con el token de invitación.
        String unusableHash = passwordEncoder.encode(tokenHasher.generateOpaqueToken());
        return userRepository.saveAndFlush(User.createPendingActivation(canonicalEmail, unusableHash, role));
    }

    private void issueInvitation(User user, Role role) {
        String rawToken = tokenHasher.generateOpaqueToken();
        Instant expiresAt = clock.instant().plus(properties.invitation().ttl());
        invitationRepository.save(Invitation.issueForExistingUser(tokenHasher.hash(rawToken),
                user.getEmail(), role, user.getId(), expiresAt));
    }

    private static StaffMemberView viewOf(User user, DentistProfile dentist) {
        return new StaffMemberView(user.getId(), user.getEmail(), user.getRole(), user.isActive(),
                user.getCreatedAt(), dentist);
    }

    public record NewDentist(String email, String firstName, String lastName, String phone,
                             String specialty, String licenseNumber, String publicInfo, String photoUrl) {
    }

    public record NewReceptionist(String email) {
    }

    /** Vista del personal para el listado; {@code dentist} es nulo para no-dentistas. */
    public record StaffMemberView(Long id, String email, Role role, boolean active, Instant createdAt,
                                  DentistProfile dentist) {
    }
}
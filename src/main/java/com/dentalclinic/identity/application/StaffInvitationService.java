package com.dentalclinic.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dentalclinic.clinic.application.DentistService;
import com.dentalclinic.clinic.application.DentistService.DentistData;
import com.dentalclinic.clinic.application.DentistService.DentistProfile;
import com.dentalclinic.identity.domain.EmailCanonicalizer;
import com.dentalclinic.identity.domain.Invitation;
import com.dentalclinic.identity.domain.IdentityEmailPurpose;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.StaffStatus;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.domain.exception.EmailAlreadyRegisteredException;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.repository.InvitationRepository;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.identity.infrastructure.security.SecurityProperties;
import com.dentalclinic.identity.infrastructure.token.TokenHasher;

/**
 * Gestión del personal por el OWNER (Fase 3 {@code /api/users}, Fase 8A edición y
 * activación/desactivación).
 *
 * <p>La invitación (Fase 3) crea una cuenta <strong>pendiente de activar</strong>
 * ({@code active=false}) y emite una invitación de un solo uso que se consume por
 * {@code POST /api/auth/activate} (F2, RF-IA-09). El rol <strong>nunca</strong> viene del cuerpo:
 * lo fija el endpoint ({@code /api/users/dentists} → {@code DENTIST}; {@code
 * /api/users/receptionists} → {@code RECEPTIONIST}). No se crea un segundo OWNER (P-07 OPEN) ni
 * pacientes por este flujo (Fase 4).
 *
 * <p><strong>Fase 8A — edición y estado (RF-PR-05, RF-PR-06):</strong> el OWNER edita los datos
 * administrativos del perfil profesional y activa/desactiva cuentas. La desactivación es un
 * <em>kill-switch</em> de acceso (§7.1): cambia {@code User.active}, revoca las sesiones vivas y no
 * borra nada — citas, expedientes e histórico permanecen (RF-PR-06). Email, rol y contraseña no
 * forman parte de ningún comando de edición: la identidad histórica asociada a citas y
 * tratamientos es estable y el rol nunca cambia por este flujo (sin escalamiento de privilegios).
 *
 * <p><strong>Entrega 8B.3.</strong> Con enlaces configurados se emite HMAC y se persisten el hash y
 * la outbox en la misma transacción. No hay SMTP en la petición ni token en respuestas de alta.
 * Sin configuración se conserva la emisión opaca legacy, sin transporte.
 *
 * <p><strong>Reenvío.</strong> {@link #reissueInvitation(Long)} es la única vía de volver a
 * entregar una invitación pendiente. No intenta recuperar el token en claro (no existe: solo hay
 * hash), sino que retira la invitación anterior y emite una nueva. El token nuevo lo
 * devuelve <strong>una sola vez</strong> al llamante. El canal de esa respuesta queda fuera de este
 * servicio: en dev lo expone un controlador con {@code @Profile("dev & !prod")}; en producción la
 * ruta no existe. La entrega usa exclusivamente la outbox opt-in, nunca la respuesta pública.
 */
@Service
public class StaffInvitationService {

    private static final Set<Role> STAFF_ROLES = EnumSet.of(Role.OWNER, Role.DENTIST, Role.RECEPTIONIST);

    private final UserRepository userRepository;
    private final InvitationRepository invitationRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenHasher tokenHasher;
    private final SecurityProperties properties;
    private final DentistService dentistService;
    private final SessionService sessionService;
    private final Clock clock;
    private final IdentityLinkIssuer linkIssuer;
    private final IdentityIssuancePolicy issuancePolicy;

    public StaffInvitationService(UserRepository userRepository,
                                  InvitationRepository invitationRepository,
                                  PasswordEncoder passwordEncoder,
                                  TokenHasher tokenHasher,
                                  SecurityProperties properties,
                                  DentistService dentistService,
                                  SessionService sessionService,
                                   Clock clock, IdentityLinkIssuer linkIssuer,
                                   IdentityIssuancePolicy issuancePolicy) {
        this.userRepository = userRepository;
        this.invitationRepository = invitationRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenHasher = tokenHasher;
        this.properties = properties;
        this.dentistService = dentistService;
        this.sessionService = sessionService;
        this.clock = clock;
        this.linkIssuer = linkIssuer;
        this.issuancePolicy = issuancePolicy;
    }

    @Transactional
    public StaffMemberView inviteDentist(NewDentist command) {
        String email = EmailCanonicalizer.canonicalize(command.email());
        User user = createPendingUser(email, Role.DENTIST);
        dentistService.createForUser(user.getId(), new DentistData(command.firstName(),
                command.lastName(), command.phone(), command.specialty(), command.licenseNumber(),
                command.publicInfo(), command.photoUrl()));
        issueInvitation(user);
        return viewOf(user, dentistService.profilesByUserIds(List.of(user.getId())).get(user.getId()));
    }

    @Transactional
    public StaffMemberView inviteReceptionist(NewReceptionist command) {
        String email = EmailCanonicalizer.canonicalize(command.email());
        User user = createPendingUser(email, Role.RECEPTIONIST);
        issueInvitation(user);
        return viewOf(user, null);
    }

    /**
     * Listado del equipo ({@code GET /api/users}): solo cuentas de personal
     * (OWNER, DENTIST, RECEPTIONIST). Las cuentas PATIENT de autoservicio no pertenecen al equipo
     * del consultorio y se descartan aquí.
     */
    @Transactional(readOnly = true)
    public List<StaffMemberView> listTeam() {
        List<User> users = userRepository.findAll(Sort.by(Sort.Direction.ASC, "createdAt")).stream()
                .filter(user -> STAFF_ROLES.contains(user.getRole()))
                .toList();
        Map<Long, DentistProfile> profiles =
                dentistService.profilesByUserIds(users.stream().map(User::getId).toList());
        Set<Long> pendingUserIds = invitationRepository.findPendingUserIds();
        return users.stream()
                .sorted(Comparator.comparing(User::getId))
                .map(user -> viewOf(user, profiles.get(user.getId()), pendingUserIds))
                .toList();
    }

    /**
     * Edición administrativa del perfil profesional de una cuenta (RF-PR-05, Fase 8A).
     *
     * <p>Solo aplica a cuentas con perfil {@code Dentist} (OWNER y DENTIST, AD-11). El email y el
     * rol viven en {@code User} y <strong>no</strong> se tocan: este comando no los incluye, la
     * identidad histórica asociada a citas y tratamientos permanece estable y no existe vía de
     * escalamiento de privilegios. Tampoco se editan contraseñas: el cambio de contraseña es un
     * flujo propio ({@code /api/auth/password/change}), nunca administrativo.
     *
     * @throws NotFoundException si la cuenta no existe
     * @throws ForbiddenOperationException si la cuenta no es personal o no tiene perfil
     *     profesional (p. ej. una cuenta de recepción: su único dato —el email— es inmutable)
     */
    @Transactional
    public StaffMemberView updateDentistProfile(Long userId, UpdateDentist command) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("No encontramos esa cuenta de personal."));
        if (!STAFF_ROLES.contains(user.getRole())) {
            throw new ForbiddenOperationException("Solo se edita el perfil del personal de la clínica.");
        }
        if (user.getRole() == Role.RECEPTIONIST) {
            throw new ForbiddenOperationException(
                    "Las cuentas de recepción no tienen datos administrativos editables; su email es inmutable.");
        }
        dentistService.updateProfileForUser(userId, new DentistData(command.firstName(),
                command.lastName(), command.phone(), command.specialty(), command.licenseNumber(),
                command.publicInfo(), command.photoUrl()));
        return viewOf(user, dentistService.profilesByUserIds(List.of(userId)).get(userId));
    }

    /**
     * Activación / desactivación administrativa de una cuenta de personal (RF-PR-05, Fase 8A).
     *
     * <p>La desactivación es el <em>kill-switch</em> de acceso de §7.1: {@code active = false}
     * invalida el login y las familias de sesión en la siguiente renovación (RF-IA-14); aquí además
     * se revocan las familias vivas de inmediato con {@link SessionService#revokeAllSessions}.
     * No es una eliminación: la cuenta, el perfil, las citas y el expediente se conservan
     * (RF-PR-06) y la reactivación restaura el acceso con la contraseña existente.
     *
     * <p>Reglas (además de la matriz §7.4, que ya reserva {@code /api/users/**} al OWNER):
     * <ul>
     *   <li>Un actor <strong>no puede cambiar su propio estado</strong>: la administración de la
     *       clínica no puede quedar sin un administrador por un clic propio.</li>
     *   <li><strong>La clínica nunca queda sin OWNER activo.</strong> La comprobación se hace con
     *       las filas de OWNER bajo bloqueo pesimista de escritura: dos desactivaciones
     *       concurrentes se serializan y la segunda ve confirmada la primera; si el resultado es
     *       cero OWNER activos, la operación se rechaza. Hoy P-07 OPEN garantiza un único OWNER,
     *       con lo cual ninguna desactivación de OWNER pasa; la guarda se expresa en términos de
     *       "queda al menos otro activo" para degradar bien si P-07 se cierra algún día.</li>
     *   <li>Las cuentas <strong>PENDIENTES</strong> (invitación sin usar) no se gestionan aquí: no
     *       tienen acceso que retirar y su estado se controla con el flujo de invitación.</li>
     * </ul>
     *
     * @return la vista actualizada, con {@code status} reflejando el nuevo estado
     * @throws NotFoundException si la cuenta no existe
     * @throws ForbiddenOperationException en cualquiera de las reglas anteriores o si la cuenta no
     *     es personal
     */
    @Transactional
    public StaffMemberView setActive(Long userId, boolean active, AuthenticatedUser actor) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("No encontramos esa cuenta de personal."));
        if (!STAFF_ROLES.contains(user.getRole())) {
            throw new ForbiddenOperationException("Solo se administra el estado del personal de la clínica.");
        }
        if (user.getId().equals(actor.userId())) {
            throw new ForbiddenOperationException(
                    "No puedes cambiar el estado de tu propia cuenta; pide a otra persona administradora que lo haga.");
        }
        if (invitationRepository.existsByUserIdAndUsedAtIsNull(user.getId())) {
            throw new ForbiddenOperationException(active
                    ? "La cuenta aún no se ha activado; actívala con el enlace de invitación, no aquí."
                    : "La cuenta aún no se ha activado; no hay acceso que retirar.");
        }
        if (!active) {
            if (user.getRole() == Role.OWNER) {
                // Lock pesimista sobre las cuentas OWNER: serializa desactivaciones concurrentes
                // para que la comprobación "queda otro OWNER activo" no se decida sobre un
                // snapshot obsoleto (dos clics simultáneos no pueden vaciar la administración).
                userRepository.findByRoleForUpdate(Role.OWNER);
                if (userRepository.countByRoleAndActiveTrueAndIdNot(Role.OWNER, user.getId()) == 0) {
                    throw new ForbiddenOperationException(
                            "La clínica no puede quedar sin una cuenta OWNER activa; activa otra propiedad antes de desactivar esta.");
                }
            }
            user.deactivate();
            sessionService.revokeAllSessions(user.getId());
        } else {
            user.activate();
        }
        userRepository.save(user);
        return viewOf(user, dentistService.profilesByUserIds(List.of(user.getId())).get(user.getId()));
    }

    /**
     * Reemite la invitación de activación de una cuenta de personal pendiente.
     *
     * <p>El token viejo solo existe como hash y no es recuperable (P-01/P-02), así que se retira
     * (se elimina la fila de {@code invitations}) y se emite un token nuevo, de un solo uso y con
     * la misma expiración que una invitación normal. El rol de la invitación es el de la cuenta: al
     * revocar y reemitir nunca puede escalar (P-07 sigue bloqueando OWNER en {@code Invitation.of}).
     *
     * @return email y token en claro; el token se entrega una única vez al llamante
     * @throws NotFoundException si la cuenta no existe
     * @throws ForbiddenOperationException si la cuenta ya está activa, ya estuvo activa
     *     ({@code INACTIVE}; se recupera con la reactivación administrativa) o no es personal
     */
    @Transactional
    public ResentInvitation reissueInvitation(Long userId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new NotFoundException("No encontramos esa cuenta de personal."));
        if (user.isActive()) {
            throw new ForbiddenOperationException(
                    "La cuenta ya está activa; no necesita un nuevo enlace de activación.");
        }
        if (user.getRole() != Role.DENTIST && user.getRole() != Role.RECEPTIONIST) {
            throw new ForbiddenOperationException("No se reenvían invitaciones de activación para esta cuenta.");
        }
        if (!invitationRepository.existsByUserIdAndUsedAtIsNull(userId)) {
            throw new ForbiddenOperationException(
                    "La cuenta ya estuvo activa; para recuperar el acceso, reactívala en lugar de reenviar la invitación.");
        }
        if (!issuancePolicy.allowStaff(userId)) {
            throw new ForbiddenOperationException("Espera antes de volver a solicitar la invitación.");
        }
        invitationRepository.deleteByUserId(userId);
        Instant expiresAt = clock.instant().plus(properties.invitation().ttl());
        String rawToken = linkIssuer.issue(user, IdentityEmailPurpose.STAFF_INVITATION, expiresAt);
        return new ResentInvitation(user.getEmail(), rawToken);
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

    private void issueInvitation(User user) {
        Instant expiresAt = clock.instant().plus(properties.invitation().ttl());
        linkIssuer.issue(user, IdentityEmailPurpose.STAFF_INVITATION, expiresAt);
    }

    private StaffMemberView viewOf(User user, DentistProfile dentist) {
        return viewOf(user, dentist,
                invitationRepository.existsByUserIdAndUsedAtIsNull(user.getId())
                        ? Set.of(user.getId())
                        : Set.of());
    }

    private static StaffMemberView viewOf(User user, DentistProfile dentist, Set<Long> pendingUserIds) {
        StaffStatus status = user.isActive()
                ? StaffStatus.ACTIVE
                : pendingUserIds.contains(user.getId()) ? StaffStatus.PENDING : StaffStatus.INACTIVE;
        return new StaffMemberView(user.getId(), user.getEmail(), user.getRole(), user.isActive(),
                user.getCreatedAt(), dentist, status);
    }

    public record NewDentist(String email, String firstName, String lastName, String phone,
                             String specialty, String licenseNumber, String publicInfo, String photoUrl) {
    }

    public record NewReceptionist(String email) {
    }

    /** Edición administrativa del perfil profesional (Fase 8A). Sin email, rol ni contraseña. */
    public record UpdateDentist(String firstName, String lastName, String phone, String specialty,
                                String licenseNumber, String publicInfo, String photoUrl) {
    }

    /** Vista del personal para el listado; {@code dentist} es nulo para no-dentistas. */
    public record StaffMemberView(Long id, String email, Role role, boolean active, Instant createdAt,
                                  DentistProfile dentist, StaffStatus status) {
    }

    /**
     * Resultado de {@link #reissueInvitation(Long)}.
     *
     * @param rawToken token en claro, único valor con vida útil: se entrega una vez al llamante y se
     *     persiste solo su hash. No se registra.
     */
    public record ResentInvitation(String email, String rawToken) {
        @Override
        public String toString() {
            return "ResentInvitation{email=<redacted>, rawToken=<redacted>}";
        }
    }
}

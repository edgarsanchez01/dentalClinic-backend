package com.dentalclinic.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.dentalclinic.appointment.domain.Appointment;
import com.dentalclinic.appointment.infrastructure.repository.AppointmentRepository;
import com.dentalclinic.clinic.application.DentistService;
import com.dentalclinic.clinic.infrastructure.repository.DentistRepository;
import com.dentalclinic.identity.IdentityIntegrationTestSupport;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.application.StaffInvitationService.NewDentist;
import com.dentalclinic.identity.application.StaffInvitationService.NewReceptionist;
import com.dentalclinic.identity.application.StaffInvitationService.UpdateDentist;
import com.dentalclinic.identity.domain.Invitation;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.StaffStatus;
import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.domain.exception.EmailAlreadyRegisteredException;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;
import com.dentalclinic.identity.infrastructure.repository.InvitationRepository;
import com.dentalclinic.identity.infrastructure.repository.RefreshSessionRepository;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;
import com.dentalclinic.identity.infrastructure.security.AuthenticatedUser;
import com.dentalclinic.identity.infrastructure.token.TokenHasher;
import com.dentalclinic.patient.domain.Patient;
import com.dentalclinic.patient.infrastructure.repository.PatientRepository;

/**
 * Invitación de personal (Fase 3): la cuenta nace pendiente de activar, con rol fijado por el
 * endpoint y una invitación de un solo uso; el token en claro no se expone.
 *
 * <p>Fase 8A: edición administrativa del perfil profesional y activación/desactivación de cuentas
 * (RF-PR-05), con sus reglas de seguridad (self, último OWNER, cuentas pendientes) y la
 * preservación del histórico (RF-PR-06).
 */
class StaffInvitationServiceIntegrationTest extends IdentityIntegrationTestSupport {

    @Autowired
    private StaffInvitationService staffInvitationService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private InvitationRepository invitationRepository;
    @Autowired
    private DentistRepository dentistRepository;
    @Autowired
    private TokenHasher tokenHasher;
    @Autowired
    private SessionService sessionService;
    @Autowired
    private RefreshSessionRepository refreshSessionRepository;
    @Autowired
    private DentistService dentistService;
    @Autowired
    private PatientRepository patientRepository;
    @Autowired
    private AppointmentRepository appointmentRepository;

    @BeforeEach
    void cleanSlate() {
        TestTime.reset();
        invitationRepository.deleteAll();
        appointmentRepository.deleteAll();
        patientRepository.deleteAll();
        dentistRepository.deleteAll();
        refreshSessionRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void invitarDentistaCreaCuentaPendientePerfilEInvitacion() {
        var member = staffInvitationService.inviteDentist(new NewDentist("dentista@clinica.test",
                "Luis", "Gomez", "+34 611 111 111", "Endodoncia", "CED-1", null, null));

        var user = userRepository.findByEmail("dentista@clinica.test").orElseThrow();
        assertThat(user.getRole()).isEqualTo(Role.DENTIST);
        assertThat(user.isActive()).as("el invitado se activa con el token, no antes").isFalse();
        assertThat(dentistRepository.findByUserId(user.getId())).isPresent();
        assertThat(invitationRepository.count()).isEqualTo(1);
        assertThat(member.active()).isFalse();
        assertThat(member.dentist()).isNotNull();
    }

    @Test
    void invitarRecepcionistaNoCreaPerfilProfesional() {
        staffInvitationService.inviteReceptionist(new NewReceptionist("recepcion@clinica.test"));

        var user = userRepository.findByEmail("recepcion@clinica.test").orElseThrow();
        assertThat(user.getRole()).isEqualTo(Role.RECEPTIONIST);
        assertThat(user.isActive()).isFalse();
        assertThat(dentistRepository.count()).isZero();
        assertThat(invitationRepository.count()).isEqualTo(1);
    }

    @Test
    void noSePuedeInvitarDosVecesElMismoEmail() {
        staffInvitationService.inviteReceptionist(new NewReceptionist("recepcion@clinica.test"));

        assertThatThrownBy(() ->
                staffInvitationService.inviteReceptionist(new NewReceptionist("recepcion@clinica.test")))
                .isInstanceOf(EmailAlreadyRegisteredException.class);
    }

    @Test
    void listadoDeEquipoSoloIncluyeRolDePersonal() {
        userRepository.saveAndFlush(User.create("duena@clinica.test", "x", Role.OWNER));
        staffInvitationService.inviteDentist(new NewDentist("dentista@clinica.test", "Luis", "Gomez",
                "+34 611 111 111", null, null, null, null));
        staffInvitationService.inviteReceptionist(new NewReceptionist("recepcion@clinica.test"));
        userRepository.saveAndFlush(User.create("paciente@clinica.test", "x", Role.PATIENT));

        assertThat(staffInvitationService.listTeam())
                .extracting(view -> view.role())
                .containsExactlyInAnyOrder(Role.OWNER, Role.DENTIST, Role.RECEPTIONIST)
                .doesNotContain(Role.PATIENT);
    }

    @Test
    void reenviarInvitacionRetiraLaAnteriorYEmiteUnTokenNuevoUsable() {
        var member = staffInvitationService.inviteDentist(new NewDentist("dentista@clinica.test",
                "Luis", "Gomez", "+34 611 111 111", null, null, null, null));
        String hashAnterior = invitationRepository.findAll().get(0).getTokenHash();

        TestTime.advance(java.time.Duration.ofMinutes(1));
        var reenviada = staffInvitationService.reissueInvitation(member.id());

        assertThat(reenviada.email()).isEqualTo("dentista@clinica.test");
        assertThat(invitationRepository.count()).as("una cuenta mantiene una sola invitación viva")
                .isEqualTo(1);
        assertThat(invitationRepository.findByTokenHash(hashAnterior))
                .as("el hash anterior queda revocado").isEmpty();

        Invitation vigente = invitationRepository.findAll().get(0);
        assertThat(vigente.getTokenHash()).isEqualTo(tokenHasher.hash(reenviada.rawToken()));
        assertThat(vigente.getUserId()).contains(member.id());
        assertThat(vigente.getRole()).isEqualTo(Role.DENTIST);
        assertThat(vigente.isUsableAt(TestTime.now())).isTrue();
    }

    @Test
    void reenviarInvitacionDeCuentaActivaEstaProhibido() {
        User activa = userRepository.saveAndFlush(
                User.create("activo@clinica.test", "x", Role.RECEPTIONIST));

        assertThatThrownBy(() -> staffInvitationService.reissueInvitation(activa.getId()))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void reenviarInvitacionDeCuentaNoPersonalEstaProhibido() {
        User paciente = userRepository.saveAndFlush(
                User.createPendingActivation("paciente@clinica.test", "x", Role.PATIENT));

        assertThatThrownBy(() -> staffInvitationService.reissueInvitation(paciente.getId()))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void reenviarInvitacionDeCuentaInexistenteEsNotFound() {
        assertThatThrownBy(() -> staffInvitationService.reissueInvitation(999_999L))
                .isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------- Fase 8A: edición de perfil

    @Test
    void editarPerfilProfesionalActualizaLosDatosSinTocarIdentidadNiContrasena() {
        var member = staffInvitationService.inviteDentist(new NewDentist("dentista@clinica.test",
                "Luis", "Gomez", "+34 611 111 111", "Endodoncia", "CED-1", "info vieja", null));
        activarCuenta(member.id());
        String hashAntes = userRepository.findById(member.id()).orElseThrow().getPasswordHash();

        var editado = staffInvitationService.updateDentistProfile(member.id(),
                new UpdateDentist("María", "López Salas", "+34 622 222 222", "Ortodoncia",
                        "CED-2", "info nueva", null));

        var user = userRepository.findById(member.id()).orElseThrow();
        var perfil = dentistRepository.findByUserId(member.id()).orElseThrow();
        assertThat(perfil.getFirstName()).isEqualTo("María");
        assertThat(perfil.getLastName()).isEqualTo("López Salas");
        assertThat(perfil.getPhone()).isEqualTo("+34 622 222 222");
        assertThat(perfil.getSpecialty()).isEqualTo("Ortodoncia");
        assertThat(perfil.getLicenseNumber()).isEqualTo("CED-2");
        assertThat(perfil.getPublicInfo()).isEqualTo("info nueva");
        assertThat(perfil.getUpdatedAt()).isEqualTo(TestTime.now());
        assertThat(user.getEmail()).as("el email es inmutable: identidad histórica")
                .isEqualTo("dentista@clinica.test");
        assertThat(user.getRole()).isEqualTo(Role.DENTIST);
        assertThat(user.getPasswordHash()).as("la edición administrativa no toca contraseñas")
                .isEqualTo(hashAntes);
        assertThat(editado.dentist().firstName()).isEqualTo("María");
        assertThat(editado.status()).isEqualTo(StaffStatus.ACTIVE);
    }

    @Test
    void editarPerfilOpcionalesANuloLimpiaElDato() {
        var member = staffInvitationService.inviteDentist(new NewDentist("dentista@clinica.test",
                "Luis", "Gomez", "+34 611 111 111", "Endodoncia", "CED-1", null, null));

        staffInvitationService.updateDentistProfile(member.id(),
                new UpdateDentist("Luis", "Gomez", "+34 611 111 111", null, null, null, null));

        var perfil = dentistRepository.findByUserId(member.id()).orElseThrow();
        assertThat(perfil.getSpecialty()).isNull();
        assertThat(perfil.getLicenseNumber()).isNull();
    }

    @Test
    void editarPerfilDeRecepcionEstaProhibido() {
        var member = staffInvitationService.inviteReceptionist(new NewReceptionist("recepcion@clinica.test"));

        assertThatThrownBy(() -> staffInvitationService.updateDentistProfile(member.id(),
                new UpdateDentist("Ana", "Recepción", "+34 600 000 000", null, null, null, null)))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void editarPerfilDeCuentaNoPersonalEstaProhibido() {
        User paciente = userRepository.saveAndFlush(
                User.create("paciente@clinica.test", "x", Role.PATIENT));

        assertThatThrownBy(() -> staffInvitationService.updateDentistProfile(paciente.getId(),
                new UpdateDentist("Paci", "Ente", "+34 600 000 000", null, null, null, null)))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void editarPerfilDeCuentaInexistenteEsNotFound() {
        assertThatThrownBy(() -> staffInvitationService.updateDentistProfile(999_999L,
                new UpdateDentist("X", "Y", "+34 600 000 000", null, null, null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------- Fase 8A: activación / desactivación

    @Test
    void desactivarCuentaRevocaSesionesYLaMarcaInactivaSinBorrarNada() {
        User recepcion = userRepository.saveAndFlush(User.create("recepcion@clinica.test",
                passwordEncoderHash("Clave-2026"), Role.RECEPTIONIST));
        sessionService.login("recepcion@clinica.test", "Clave-2026");
        var actor = new AuthenticatedUser(1L, Role.OWNER);

        var vista = staffInvitationService.setActive(recepcion.getId(), false, actor);

        assertThat(vista.active()).isFalse();
        assertThat(vista.status()).isEqualTo(StaffStatus.INACTIVE);
        assertThat(userRepository.findById(recepcion.getId()).orElseThrow().isActive()).isFalse();
        assertThat(refreshSessionRepository.findByUserIdAndRevokedAtIsNull(recepcion.getId()))
                .as("la desactivación revoca las familias vivas al instante")
                .isEmpty();
        assertThatThrownBy(() -> sessionService.login("recepcion@clinica.test", "Clave-2026"))
                .isInstanceOf(com.dentalclinic.identity.domain.exception.AccountInactiveException.class);
    }

    @Test
    void reactivarCuentaRestauraElAccesoConLaContrasenaExistente() {
        User recepcion = userRepository.saveAndFlush(User.create("recepcion@clinica.test",
                passwordEncoderHash("Clave-2026"), Role.RECEPTIONIST));
        var actor = new AuthenticatedUser(1L, Role.OWNER);
        staffInvitationService.setActive(recepcion.getId(), false, actor);

        var vista = staffInvitationService.setActive(recepcion.getId(), true, actor);

        assertThat(vista.active()).isTrue();
        assertThat(vista.status()).isEqualTo(StaffStatus.ACTIVE);
        assertThat(sessionService.login("recepcion@clinica.test", "Clave-2026").user().getId())
                .isEqualTo(recepcion.getId());
    }

    @Test
    void noSePuedeCambiarElEstadoDeLaPropiaCuenta() {
        User recepcion = userRepository.saveAndFlush(
                User.create("recepcion@clinica.test", "x", Role.RECEPTIONIST));
        var propio = new AuthenticatedUser(recepcion.getId(), Role.RECEPTIONIST);

        assertThatThrownBy(() -> staffInvitationService.setActive(recepcion.getId(), false, propio))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThat(userRepository.findById(recepcion.getId()).orElseThrow().isActive())
                .as("la cuenta sigue activa: la operación se revirtió").isTrue();
    }

    @Test
    void noSePuedeDesactivarLaUnicaCuentaOwnerDeLaClinica() {
        User owner = userRepository.saveAndFlush(
                User.create("duena@clinica.test", "x", Role.OWNER));
        User recepcion = userRepository.saveAndFlush(
                User.create("recepcion@clinica.test", "x", Role.RECEPTIONIST));
        // El rol del actor lo valida el controller (EndpointGroup USERS); aquí solo importa que
        // no sea la propia cuenta objetivo.
        var actor = new AuthenticatedUser(recepcion.getId(), Role.RECEPTIONIST);

        assertThatThrownBy(() -> staffInvitationService.setActive(owner.getId(), false, actor))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("OWNER activa");
        assertThat(userRepository.findById(owner.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void desactivarCuentaPendienteEstaProhibidoEnAmbasDirecciones() {
        var pendiente = staffInvitationService.inviteReceptionist(new NewReceptionist("recepcion@clinica.test"));
        var actor = new AuthenticatedUser(1L, Role.OWNER);

        assertThatThrownBy(() -> staffInvitationService.setActive(pendiente.id(), false, actor))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("no se ha activado");
        assertThatThrownBy(() -> staffInvitationService.setActive(pendiente.id(), true, actor))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void desactivarCuentaNoPersonalEstaProhibido() {
        User paciente = userRepository.saveAndFlush(
                User.create("paciente@clinica.test", "x", Role.PATIENT));
        var actor = new AuthenticatedUser(1L, Role.OWNER);

        assertThatThrownBy(() -> staffInvitationService.setActive(paciente.getId(), false, actor))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void cambiarEstadoDeCuentaInexistenteEsNotFound() {
        assertThatThrownBy(() -> staffInvitationService.setActive(999_999L, false,
                new AuthenticatedUser(1L, Role.OWNER)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void elListadoDistinguePendienteInactivaYActiva() {
        var pendiente = staffInvitationService.inviteDentist(new NewDentist("pendiente@clinica.test",
                "Ana", "Perez", "+34 611 000 000", null, null, null, null));
        var activa = staffInvitationService.inviteDentist(new NewDentist("activa@clinica.test",
                "Bea", "Ruiz", "+34 611 000 001", null, null, null, null));
        activarCuenta(activa.id());
        var desactivada = staffInvitationService.inviteDentist(new NewDentist("baja@clinica.test",
                "Caro", "Vega", "+34 611 000 002", null, null, null, null));
        activarCuenta(desactivada.id());
        staffInvitationService.setActive(desactivada.id(), false, new AuthenticatedUser(activa.id(), Role.DENTIST));

        var porId = staffInvitationService.listTeam().stream()
                .collect(java.util.stream.Collectors.toMap(StaffInvitationService.StaffMemberView::id,
                        StaffInvitationService.StaffMemberView::status));

        assertThat(porId.get(pendiente.id())).isEqualTo(StaffStatus.PENDING);
        assertThat(porId.get(activa.id())).isEqualTo(StaffStatus.ACTIVE);
        assertThat(porId.get(desactivada.id())).isEqualTo(StaffStatus.INACTIVE);
    }

    @Test
    void reenviarInvitacionDeCuentaInactivaEstaProhibido() {
        var member = staffInvitationService.inviteReceptionist(new NewReceptionist("recepcion@clinica.test"));
        activarCuenta(member.id());
        staffInvitationService.setActive(member.id(), false, new AuthenticatedUser(1L, Role.OWNER));

        assertThatThrownBy(() -> staffInvitationService.reissueInvitation(member.id()))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("reactívala");
    }

    // ------------------------------------------------------- Fase 8A: RF-PR-06 y concurrencia

    @Test
    void desactivarProfesionalPreservaCitasYExpediente() {
        var member = staffInvitationService.inviteDentist(new NewDentist("dentista@clinica.test",
                "Luis", "Gomez", "+34 611 111 111", null, null, null, null));
        activarCuenta(member.id());
        Patient paciente = patientRepository.save(Patient.create("Eva", "Paciente",
                "eva@clinica.test", "+34 600 111 222", java.time.LocalDate.of(1990, 1, 1), null));
        Appointment cita = appointmentRepository.save(Appointment.create(paciente.getId(),
                member.id(), member.id(), TestTime.now().plusSeconds(3600),
                TestTime.now().plusSeconds(3900), 30, "Revisión", null));

        staffInvitationService.setActive(member.id(), false, new AuthenticatedUser(1L, Role.OWNER));

        assertThat(appointmentRepository.findById(cita.getId()))
                .as("RF-PR-06: la desactivación no elimina citas")
                .isPresent();
        assertThat(dentistRepository.findByUserId(member.id()))
                .as("RF-PR-06: el perfil profesional se conserva")
                .isPresent();
        assertThat(userRepository.findById(member.id()))
                .as("la cuenta existe y sigue desactivada; no hay eliminación física")
                .isPresent();
    }

    @Test
    void dosDesactivacionesConcurrentesNoDejanLaClinicaSinOwner() throws Exception {
        // Escenario futuro (P-07 hoy lo impide): dos cuentas OWNER activas. Dos desactivaciones
        // simultáneas deben serializarse; exactamente una puede ganar y nunca quedan cero activos.
        User ownerA = userRepository.saveAndFlush(
                User.create("duena-a@clinica.test", "x", Role.OWNER));
        User ownerB = userRepository.saveAndFlush(
                User.create("duena-b@clinica.test", "x", Role.OWNER));
        var actorA = new AuthenticatedUser(ownerA.getId(), Role.OWNER);
        var actorB = new AuthenticatedUser(ownerB.getId(), Role.OWNER);

        CountDownLatch listos = new CountDownLatch(2);
        CountDownLatch disparo = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            // Cada hilo intenta desactivar a la OTRA cuenta OWNER (el self-guard corta el mismo
            // id, y aquí queremos ejercitar la guarda de "último OWNER" bajo el lock).
            Future<?> hiloA = pool.submit(() -> {
                listos.countDown();
                await(disparo);
                return staffInvitationService.setActive(ownerB.getId(), false, actorA);
            });
            Future<?> hiloB = pool.submit(() -> {
                listos.countDown();
                await(disparo);
                return staffInvitationService.setActive(ownerA.getId(), false, actorB);
            });
            assertThat(listos.await(5, TimeUnit.SECONDS)).isTrue();
            disparo.countDown();

            int ganadas = 0;
            for (Future<?> hilo : List.of(hiloA, hiloB)) {
                try {
                    hilo.get(30, TimeUnit.SECONDS);
                    ganadas++;
                } catch (java.util.concurrent.ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ForbiddenOperationException.class);
                }
            }
            assertThat(ganadas).as("solo una desactivación puede ganar").isEqualTo(1);
        }
        long ownersActivos = userRepository.findAll().stream()
                .filter(u -> u.getRole() == Role.OWNER && u.isActive())
                .count();
        assertThat(ownersActivos)
                .as("la instalación nunca queda sin OWNER activo")
                .isEqualTo(1);
    }

    // ------------------------------------------------------- helpers

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch no liberado");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String passwordEncoderHash(String raw) {
        return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(raw);
    }

    /** Simula el consumo de la invitación (POST /api/auth/activate) sin exponer el token. */
    private void activarCuenta(Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        user.activate();
        userRepository.save(user);
        invitationRepository.findAll().stream()
                .filter(invitation -> invitation.getUserId().filter(userId::equals).isPresent())
                .forEach(invitation -> {
                    invitation.markUsedAt(TestTime.now(), userId);
                    invitationRepository.save(invitation);
                });
    }
}

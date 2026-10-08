package com.dentalclinic.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.dentalclinic.clinic.infrastructure.repository.DentistRepository;
import com.dentalclinic.identity.IdentityIntegrationTestSupport;
import com.dentalclinic.identity.TestTime;
import com.dentalclinic.identity.application.StaffInvitationService.NewDentist;
import com.dentalclinic.identity.application.StaffInvitationService.NewReceptionist;
import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.exception.EmailAlreadyRegisteredException;
import com.dentalclinic.identity.infrastructure.repository.InvitationRepository;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;

/**
 * Invitación de personal (Fase 3): la cuenta nace pendiente de activar, con rol fijado por el
 * endpoint y una invitación de un solo uso; el token en claro no se expone.
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

    @BeforeEach
    void cleanSlate() {
        TestTime.reset();
        invitationRepository.deleteAll();
        dentistRepository.deleteAll();
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
}
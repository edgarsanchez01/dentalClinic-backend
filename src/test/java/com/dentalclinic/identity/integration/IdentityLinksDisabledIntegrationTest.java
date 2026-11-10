package com.dentalclinic.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.dentalclinic.identity.application.StaffInvitationService.NewReceptionist;
import com.dentalclinic.identity.domain.Role;

/** Compatibilidad operativa: flags apagados y sin clave no crean entrega ni rompen el modo dev. */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:16:///dentalclinic_identity_flows_disabled",
        "dentalclinic.identity.links-enabled=false",
        "dentalclinic.identity.link-secret=",
        "dentalclinic.identity.public-base-url="
})
class IdentityLinksDisabledIntegrationTest extends IdentityLinkFlowTestSupport {
    @Test
    void modoLegacySinConfiguracionNoEnviaYPermiteConsumoDev() {
        var member = staff.inviteReceptionist(new NewReceptionist("disabled-staff@dc.test"));
        var resent = staff.reissueInvitation(member.id());
        activation.activate(resent.rawToken(), NEW_PASSWORD);
        assertThat(users.findById(member.id()).orElseThrow().isActive()).isTrue();
        var user = active("disabled-reset@dc.test", Role.PATIENT);
        passwords.requestReset(user.getEmail());
        assertThat(resets.count()).isEqualTo(1);
        assertThat(outbox.count()).isZero();
        assertThat(dispatcher.dispatchOnce()).isZero();
        assertThat(sender.sentCount()).isZero();
    }
}

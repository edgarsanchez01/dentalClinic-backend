package com.dentalclinic.identity.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.dentalclinic.identity.application.IdentityRequestLimiter;
import com.dentalclinic.identity.application.StaffInvitationService;
import com.dentalclinic.identity.application.StaffInvitationService.ResentInvitation;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.infrastructure.security.IdentityClientAddress;

class IdentityDevInvitationControllerTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(DevInvitationController.class)
            .withBean(StaffInvitationService.class, () -> mock(StaffInvitationService.class))
            .withBean(IdentityRequestLimiter.class, () -> mock(IdentityRequestLimiter.class))
            .withBean(IdentityClientAddress.class, () -> mock(IdentityClientAddress.class));

    @Test
    void perfilDevMantieneMecanismoYProdInclusoCombinadoLoExcluye() {
        context.withPropertyValues("spring.profiles.active=dev").run(ctx ->
                assertThat(ctx).hasSingleBean(DevInvitationController.class));
        context.withPropertyValues("spring.profiles.active=prod").run(ctx ->
                assertThat(ctx).doesNotHaveBean(DevInvitationController.class));
        context.withPropertyValues("spring.profiles.active=dev,prod").run(ctx ->
                assertThat(ctx).doesNotHaveBean(DevInvitationController.class));
    }

    @Test
    void respuestaDevNoSeCacheaYElLimiteIpEvitaReemision() {
        var staff = mock(StaffInvitationService.class);
        var limiter = mock(IdentityRequestLimiter.class);
        var address = mock(IdentityClientAddress.class);
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var controller = new DevInvitationController(staff, limiter, address);
        when(address.resolve(request)).thenReturn("192.0.2.1");
        when(limiter.allowStaffIp("192.0.2.1")).thenReturn(false);
        assertThatThrownBy(() -> controller.resend(1L, request, response))
                .isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(staff);
        when(limiter.allowStaffIp("192.0.2.1")).thenReturn(true);
        when(staff.reissueInvitation(1L)).thenReturn(new ResentInvitation("dev@dc.test", "dev-secret-token"));
        var result = controller.resend(1L, request, response);
        assertThat(result.token()).isEqualTo("dev-secret-token");
        assertThat(result.toString()).doesNotContain("dev-secret-token");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }
}

package com.dentalclinic.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.dentalclinic.appointment.application.EmailSender;
import com.dentalclinic.identity.infrastructure.config.IdentityEmailProperties;
import com.dentalclinic.identity.infrastructure.config.IdentityLinkProperties;

/**
 * F8B.2: sin adaptador SMTP o sin enlaces configurados, el dispatcher no envía, no reclama y no
 * consume ningún intento. Un envío apagado o simulado nunca puede marcar una entrega {@code SENT}.
 */
class IdentityEmailDispatcherDisabledTest {

    private static final IdentityEmailProperties PROPERTIES = new IdentityEmailProperties(
            false, 0, 0, null, 0, null, null, null, null);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T08:00:00Z"),
            ZoneOffset.UTC);

    @Test
    void sinEmisorNoEnviaNiConsumeIntentos() {
        IdentityEmailClaimService claims = mock(IdentityEmailClaimService.class);
        IdentityLinkProperties links = new IdentityLinkProperties("http://localhost:5173",
                "x".repeat(32), true);

        IdentityEmailDispatcher dispatcher = new IdentityEmailDispatcher(claims,
                mock(IdentityEmailComposer.class), PROPERTIES, links, Optional.empty(), CLOCK);

        assertThat(dispatcher.isDeliveryConfigured()).isFalse();
        assertThat(dispatcher.dispatchOnce()).isZero();
        verifyNoInteractions(claims);
    }

    @Test
    void sinEnlacesConfiguradosNoEnviaNiConsumeIntentos() {
        IdentityEmailClaimService claims = mock(IdentityEmailClaimService.class);
        EmailSender sender = mock(EmailSender.class);
        IdentityLinkProperties links = new IdentityLinkProperties("", "", false);

        IdentityEmailDispatcher dispatcher = new IdentityEmailDispatcher(claims,
                mock(IdentityEmailComposer.class), PROPERTIES, links, Optional.of(sender), CLOCK);

        assertThat(dispatcher.isDeliveryConfigured()).isFalse();
        assertThat(dispatcher.dispatchOnce()).isZero();
        verifyNoInteractions(claims);
        verifyNoInteractions(sender);
    }
}

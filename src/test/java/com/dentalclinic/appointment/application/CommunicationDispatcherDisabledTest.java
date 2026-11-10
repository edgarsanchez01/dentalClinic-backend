package com.dentalclinic.appointment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.dentalclinic.appointment.infrastructure.config.CommunicationProperties;

/**
 * F8/T1: sin adaptador SMTP el dispatcher no envía ni marca nada. Un envío deshabilitado o simulado
 * nunca puede convertir una comunicación en {@code SENT}.
 */
class CommunicationDispatcherDisabledTest {

    @Test
    void sinEmisorNoEnviaNiConsumeIntentos() {
        CommunicationClaimService claims = mock(CommunicationClaimService.class);
        CommunicationProperties properties = new CommunicationProperties(
                new CommunicationProperties.Email(false, null),
                new CommunicationProperties.Dispatch(false, 0, 0, null, 0, null, null, null, null));
        Clock clock = Clock.fixed(Instant.parse("2026-10-06T08:00:00Z"), ZoneOffset.UTC);

        CommunicationDispatcher dispatcher = new CommunicationDispatcher(claims,
                mock(CommunicationEmailComposer.class), properties, Optional.empty(), clock,
                mock(com.dentalclinic.feedback.application.FeedbackService.class));

        assertThat(dispatcher.isDeliveryConfigured()).isFalse();
        assertThat(dispatcher.dispatchOnce()).isZero();
        verifyNoInteractions(claims);
    }
}

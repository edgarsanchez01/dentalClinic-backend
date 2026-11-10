package com.dentalclinic.appointment.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Registra el emisor falso como el único {@code EmailSender} del contexto de test. Como
 * {@code dentalclinic.communication.email.enabled} no se activa, el adaptador SMTP real no existe:
 * los tests no pueden enviar correo real aunque quisieran.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeEmailSenderConfiguration {

    @Bean
    public RecordingEmailSender recordingEmailSender() {
        return new RecordingEmailSender();
    }
}

package com.dentalclinic.appointment.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

import com.dentalclinic.appointment.application.EmailSender;
import com.dentalclinic.appointment.infrastructure.email.JavaMailEmailSender;

/**
 * Adaptador SMTP del canal EMAIL (F8/T1).
 *
 * <p>Solo se crea cuando el operador habilita explícitamente el canal con
 * {@code dentalclinic.communication.email.enabled=true} <strong>y</strong> el entorno define
 * {@code spring.mail.host} (por ejemplo {@code SPRING_MAIL_HOST}). Sin esa configuración no existe
 * {@link EmailSender}: el dispatcher no envía y ninguna comunicación se marca {@code SENT}
 * (los mensajes quedan {@code PENDING}).
 *
 * <p>Las credenciales SMTP ({@code SPRING_MAIL_USERNAME}/{@code SPRING_MAIL_PASSWORD}) nunca están
 * en el repositorio: las consume la autoconfiguración de Spring Boot.
 */
@Configuration(proxyBeanMethods = false)
public class CommunicationConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "dentalclinic.communication.email", name = "enabled",
            havingValue = "true")
    public EmailSender javaMailEmailSender(JavaMailSender mailSender,
            CommunicationProperties properties) {
        return new JavaMailEmailSender(mailSender, properties.email().from());
    }
}

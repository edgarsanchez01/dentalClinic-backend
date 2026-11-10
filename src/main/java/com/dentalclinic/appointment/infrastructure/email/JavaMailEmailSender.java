package com.dentalclinic.appointment.infrastructure.email;

import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.StringUtils;

import com.dentalclinic.appointment.application.EmailSendException;
import com.dentalclinic.appointment.application.EmailSender;
import com.dentalclinic.appointment.application.OutboundEmail;

/**
 * Adaptador SMTP del canal EMAIL (Architecture.md 9.2/9.5).
 *
 * <p>Se construye <strong>solo</strong> cuando {@code dentalclinic.communication.email.enabled=true}
 * y el entorno define {@code spring.mail.host} (por ejemplo vía {@code SPRING_MAIL_HOST}). Las
 * credenciales SMTP llegan por variables de entorno y nunca están en el código ni en los logs.
 *
 * <p>Traduce cualquier {@link MailException} a {@link EmailSendException}: el dispatcher decide si
 * reintenta o agota, sin acoplarse a la librería de correo.
 */
public class JavaMailEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String from;

    public JavaMailEmailSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        if (!StringUtils.hasText(from)) {
            throw new IllegalStateException(
                    "dentalclinic.communication.email.from es obligatorio cuando el canal EMAIL "
                            + "esta habilitado");
        }
        this.from = from;
    }

    @Override
    public void send(OutboundEmail email) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email.to());
        message.setSubject(email.subject());
        message.setText(email.body());
        try {
            mailSender.send(message);
        } catch (MailException ex) {
            throw new EmailSendException("Fallo SMTP al enviar comunicacion", ex);
        }
    }
}

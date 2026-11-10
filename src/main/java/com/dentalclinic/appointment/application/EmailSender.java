package com.dentalclinic.appointment.application;

/**
 * Puerto de envío de correo (Architecture.md 9.2: {@code CommunicationChannel}/{@code EmailChannel}).
 *
 * <p>El dominio y el dispatcher hablan contra esta interfaz mínima; SMTP es solo un adaptador
 * ({@code JavaMailEmailSender}). En las pruebas se sustituye por un emisor falso que no abre red.
 *
 * <p>Contrato: {@link #send} <strong>solo</strong> retorna normalmente cuando el proveedor aceptó
 * el envío; cualquier fallo debe propagarse como {@link EmailSendException} (o subclase). El
 * dispatcher marca {@code SENT} únicamente tras un retorno exitoso.
 */
public interface EmailSender {

    void send(OutboundEmail email);
}

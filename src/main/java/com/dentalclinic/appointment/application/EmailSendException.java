package com.dentalclinic.appointment.application;

/**
 * Fallo de entrega del correo. Su mensaje se persiste como {@code last_error} (saneado) y se
 * registra en logs, por lo que nunca debe contener credenciales ni contenido clínico sensible.
 */
public class EmailSendException extends RuntimeException {

    public EmailSendException(String message) {
        super(message);
    }

    public EmailSendException(String message, Throwable cause) {
        super(message, cause);
    }
}

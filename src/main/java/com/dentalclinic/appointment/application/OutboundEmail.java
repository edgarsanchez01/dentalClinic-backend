package com.dentalclinic.appointment.application;

/**
 * Correo ya renderizado listo para el adaptador de canal. Es un valor inmutable: no lleva la
 * entidad {@code Communication} ni datos clínicos, solo lo estrictamente necesario para enviar.
 */
public record OutboundEmail(String to, String subject, String body) {
    @Override
    public String toString() {
        return "OutboundEmail{to=<redacted>, subject=<redacted>, body=<redacted>}";
    }
}

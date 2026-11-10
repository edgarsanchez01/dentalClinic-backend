package com.dentalclinic.appointment.application;

import java.util.Optional;

/**
 * Puerto interno de lectura para el registro durable del cierre (UX-05B.1, Etapa 3.2).
 *
 * <p>Mantiene el acceso al repositorio de citas dentro del modulo {@code appointment}: el modulo
 * {@code checkout} no importa repositorios ajenos. No autoriza, no abre transaccion y no se expone
 * por HTTP. El futuro coordinador conserva el gate de actor y visibilidad.
 */
public interface AppointmentCheckoutReferenceReader {

    Optional<Reference> findById(Long appointmentId);

    /**
     * Bloquea la cita y devuelve la misma referencia minima. Lo usa el alta administrativa de un
     * cargo con cita para entrar en el protocolo comun de locks: clave HTTP, cita y despues el
     * alta. No autoriza por si mismo ni abre transaccion.
     */
    Optional<Reference> lockById(Long appointmentId);

    /** Datos minimos para validar las invariantes cruzadas del cierre. */
    record Reference(Long id, Long patientId, int occurrenceVersion) {
    }
}

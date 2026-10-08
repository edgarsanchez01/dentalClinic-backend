package com.dentalclinic.scheduling.application;

import java.time.Instant;
import java.util.List;

/**
 * Puerto de lectura de ocupación de citas (dependency inversion, §6.3/§6.4).
 *
 * <p>El módulo de citas y el de scheduling no pueden acoplarse en ciclos (Appointments depende de
 * Scheduling, nunca al revés). Scheduling declara aquí qué necesita saber de las citas y el módulo
 * {@code appointment} implementa el puerto en su capa de aplicación. Devolver intervalos permite a
 * {@link AvailabilityService} sustraer las citas {@code SCHEDULED} de la disponibilidad calculada
 * (A-14) con el MISMO predicado que la constraint EXCLUDE de V006.
 */
public interface AppointmentOccupancyReader {

    /**
     * Intervalos de citas {@code SCHEDULED} que solapan {@code [startAt, endAt)} (sentido estricto
     * del medio-abierto), excluyendo {@code excludeAppointmentId} (la propia cita al reprogramar).
     *
     * @return lista vacía si el intervalo está libre; nunca {@code null}
     */
    List<OccupiedInterval> overlapping(Long dentistId, Instant startAt, Instant endAt,
                                       Long excludeAppointmentId);

    /**
     * Todos los intervalos {@code SCHEDULED} de un dentista que solapan el rango
     * {@code [from, to)}. Sirve para sustraer en lote, una sola consulta por rango de agenda (A-14).
     *
     * @return lista vacía si no hay ocupación; nunca {@code null}
     */
    List<OccupiedInterval> occupiedIntervalsInRange(Long dentistId, Instant from, Instant to);

    /** Intervalo ocupado de una cita, en clave {@code [start, end)}. */
    record OccupiedInterval(Long appointmentId, Instant start, Instant end) {
    }
}
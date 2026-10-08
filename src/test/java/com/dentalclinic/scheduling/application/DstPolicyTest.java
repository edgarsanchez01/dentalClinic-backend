package com.dentalclinic.scheduling.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Política DST conservadora de la disponibilidad (decisión de Fase 5, RF-AV).
 *
 * <p>Un slot solo es válido si inicio y fin tienen una única interpretación y la duración real
 * coincide con la solicitada. Nunca se elige el offset anterior ni se desplaza un horario en gap.
 */
class DstPolicyTest {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    @Test
    void diaNormalResuelveElInstanteEsperado() {
        Optional<Instant> start = AvailabilityService.resolveStart(
                LocalDate.of(2026, 10, 6), LocalTime.of(9, 0), 30, MADRID);
        assertThat(start).contains(Instant.parse("2026-10-06T07:00:00Z"));
    }

    @Test
    void inicioEnGapPrimaveraSeRechaza() {
        // 2027-03-28 02:30 no existe en Madrid (salto 02:00 -> 03:00).
        Optional<Instant> start = AvailabilityService.resolveStart(
                LocalDate.of(2027, 3, 28), LocalTime.of(2, 30), 30, MADRID);
        assertThat(start).isEmpty();
    }

    @Test
    void finEnGapPrimaveraSeRechaza() {
        Optional<Instant> start = AvailabilityService.resolveStart(
                LocalDate.of(2027, 3, 28), LocalTime.of(2, 45), 30, MADRID);
        assertThat(start).isEmpty();
    }

    @Test
    void inicioEnOverlapOtonoSeRechaza() {
        // 2026-10-25 02:30 ocurre dos veces en Madrid: se rechaza por ambiguo.
        Optional<Instant> start = AvailabilityService.resolveStart(
                LocalDate.of(2026, 10, 25), LocalTime.of(2, 30), 30, MADRID);
        assertThat(start).isEmpty();
    }

    @Test
    void finEnOverlapOtonoSeRechaza() {
        // 2026-10-25 01:45 + 30m termina en 02:15, hora que ocurre dos veces: se rechaza.
        Optional<Instant> start = AvailabilityService.resolveStart(
                LocalDate.of(2026, 10, 25), LocalTime.of(1, 45), 30, MADRID);
        assertThat(start).isEmpty();
    }

    @Test
    void horarioNormalConservaLaHoraDeReloj() {
        // 09:00 de un día de verano debe seguir siendo 09:00 wall-clock (CEST = UTC+2).
        Optional<Instant> start = AvailabilityService.resolveStart(
                LocalDate.of(2026, 7, 6), LocalTime.of(9, 0), 60, MADRID);
        assertThat(start).contains(Instant.parse("2026-07-06T07:00:00Z"));
    }

    @Test
    void intervaloQueCruzaLaTransicionSeRechaza() {
        // Empieza antes del salto y termina despues: la duracion real no es la solicitada.
        Optional<Instant> start = AvailabilityService.resolveStart(
                LocalDate.of(2027, 3, 28), LocalTime.of(1, 30), 90, MADRID);
        assertThat(start).isEmpty();
    }

    @Test
    void slotJustoDespuesDelGapEsValido() {
        Optional<Instant> start = AvailabilityService.resolveStart(
                LocalDate.of(2027, 3, 28), LocalTime.of(4, 0), 30, MADRID);
        assertThat(start).contains(Instant.parse("2027-03-28T02:00:00Z"));
    }
}
package com.dentalclinic.identity;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Reloj fijo de tests: lee {@link TestTime}, el {@code AtomicReference} que cada test avanza.
 *
 * <p>Al estar marcado {@code @Primary}, gana sobre el {@code Clock.systemUTC()} de
 * TimeConfiguration dentro de un contexto de test. Su única responsabilidad técnica es leer el
 * instante de la fuente configurable; no interfiere con la lógica de negocio.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestTimeConfig {

    @Bean
    @Primary
    public Clock testClock() {
        return new RefClock();
    }

    private static final class RefClock extends Clock {

        @Override
        public Instant instant() {
            return TestTime.now();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
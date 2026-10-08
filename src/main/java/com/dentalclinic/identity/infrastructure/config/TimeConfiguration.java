package com.dentalclinic.identity.infrastructure.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fuente de tiempo única de la aplicación.
 *
 * <p>Toda caducidad (access token, refresh, invitación, reset) se calcula con este reloj y no con
 * {@code Instant.now()} disperso por el código. Así los tests pueden fijar el instante y comprobar
 * de forma determinista los bordes de expiración, que en un flujo de tokens es donde se esconden
 * los errores.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
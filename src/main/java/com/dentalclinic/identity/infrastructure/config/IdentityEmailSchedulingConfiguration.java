package com.dentalclinic.identity.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import com.dentalclinic.identity.application.IdentityEmailDispatcher;

/**
 * Programa el dispatcher de enlaces de identidad (F8B.2), independiente del de comunicaciones.
 *
 * <p>Requiere activación operativa explícita ({@code dentalclinic.identity.email.dispatch-enabled},
 * por defecto {@code false}). Con la bandera apagada no se registra ninguna tarea: los tests y los
 * entornos actuales no ven ningún job nuevo. La frecuencia y el retardo inicial vienen de
 * {@link IdentityEmailProperties}.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class IdentityEmailSchedulingConfiguration implements SchedulingConfigurer {

    private final IdentityEmailDispatcher dispatcher;
    private final IdentityEmailProperties properties;

    public IdentityEmailSchedulingConfiguration(IdentityEmailDispatcher dispatcher,
            IdentityEmailProperties properties) {
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        if (properties.dispatchEnabled()) {
            taskRegistrar.addFixedDelayTask(new FixedDelayTask(dispatcher::dispatchOnce,
                    properties.interval(), properties.initialDelay()));
        }
    }
}

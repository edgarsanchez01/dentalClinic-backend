package com.dentalclinic.appointment.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuración técnica de T4.1; las cinco horas son una regla fija de producto. */
@ConfigurationProperties(prefix = "dentalclinic.appointment.reminder")
public record AppointmentReminderProperties(boolean enabled, int batchSize, int maxBatches,
        Duration interval, Duration initialDelay, boolean emailDeliveryEnabled) {

    public AppointmentReminderProperties {
        batchSize = batchSize <= 0 ? 50 : batchSize;
        maxBatches = maxBatches <= 0 ? 10 : maxBatches;
        interval = interval == null ? Duration.ofSeconds(60) : interval;
        initialDelay = initialDelay == null ? Duration.ofSeconds(30) : initialDelay;
        if (interval.isNegative() || interval.isZero() || initialDelay.isNegative()) {
            throw new IllegalArgumentException("El intervalo debe ser positivo y el retardo no negativo.");
        }
    }
}

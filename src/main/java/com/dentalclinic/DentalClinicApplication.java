package com.dentalclinic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Punto de entrada del backend DentalClinic.
 *
 * <p>Fase 1 - Fundacion: solo infraestructura tecnica. Esta clase no crea
 * datos de ninguna clase (Agents.md Regla 11 y Regla 12, Architecture.md 13.1).
 * No se anota con ningun perfil ni se convierte en un runner de bootstrap.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DentalClinicApplication {

    public static void main(String[] args) {
        SpringApplication.run(DentalClinicApplication.class, args);
    }
}
package com.dentalclinic.foundation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propiedades de las guardas de fixtures (Architecture.md 13.3).
 *
 * <p>Estas propiedades NO habilitan por si solas las fixtures: la primera barrera
 * es el aislamiento por perfil de Spring. Este bloque solo existe para que el
 * resto de barreras sea configurable y auditable.
 *
 * @param enabled             bandera explicita de entorno (segunda barrera)
 * @param allowedProfiles     perfiles de Spring autorizados a sembrar
 * @param expectedDatabase    nombre de base de datos que debe coincidir
 * @param requireEmptyDatabase si true, la base debe estar vacia de tablas de negocio
 * @param forbiddenDatabases  nombres de base que nunca se pueden sembrar
 */
@ConfigurationProperties(prefix = "dentalclinic.fixtures")
public record FixturesProperties(
        boolean enabled,
        java.util.Set<String> allowedProfiles,
        String expectedDatabase,
        boolean requireEmptyDatabase,
        java.util.Set<String> forbiddenDatabases) {

    public FixturesProperties {
        allowedProfiles = allowedProfiles == null || allowedProfiles.isEmpty()
                ? java.util.Set.of("dev", "demo")
                : java.util.Set.copyOf(allowedProfiles);
        forbiddenDatabases = forbiddenDatabases == null || forbiddenDatabases.isEmpty()
                ? java.util.Set.of("dental", "postgres", "production", "prod")
                : java.util.Set.copyOf(forbiddenDatabases);
    }

    /** Estado por defecto cuando la propiedad no esta presente. Todo desactivado. */
    public static FixturesProperties disabled() {
        return new FixturesProperties(false, java.util.Set.of(), null, true, java.util.Set.of());
    }
}
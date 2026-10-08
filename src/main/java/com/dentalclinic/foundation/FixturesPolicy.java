package com.dentalclinic.foundation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Politica pura de decision de las guardas de fixtures (Architecture.md 13.3,
 * Agents.md Regla 14). Es logica sin Spring a proposito: se puede probar de forma
 * exhaustiva y auditable sin base de datos.
 *
 * <p>Las cuatro barreras ("defense in depth") se evaluan siempre y en orden:
 *
 * <ol>
 *   <li>Perfil de Spring. En codigo corresponde al {@code @Profile} del seeder;
 *       aqui se comprueba como condicion logica para que la decision sea auditable.</li>
 *   <li>Bandera explicita de entorno ({@code dentalclinic.fixtures.enabled}).</li>
 *   <li>Verificacion de base de datos: nombre esperado y no prohibido.</li>
 *   <li>Opcionalmente, base de datos vacia de tablas de negocio.</li>
 * </ol>
 *
 * <p>No existe combinacion de banderas que habilite la siembra si una barrera falla.
 */
public final class FixturesPolicy {

    /** Tablas tecnicas de infraestructura: nunca son datos de negocio. */
    public static final Set<String> TECHNICAL_TABLES = Set.of(
            "app_schema_version",
            "schema_version",
            "flyway_schema_history",
            "liquibase_databasechangelog",
            "hibernate_sequence",
            // Marker tecnico de instalacion (B-04): metadato, no dato de negocio.
            "installation_state");

    private FixturesPolicy() {
    }

    public enum Status {
        /** nobody pidio fixtures: no se siembra nada y no hay nada que bloquear. */
        NOT_REQUESTED,
        /** Todas las barreras pasan: la siembra es segura. */
        ALLOWED,
        /** Se pidieron fixtures y al menos una barrera fallo: hay que detener el proceso. */
        BLOCKED
    }

    /**
     * @param status        resultado de la evaluacion
     * @param failedBarriers barreras que fallaron, en orden de evaluacion
     */
    public record Verdict(Status status, List<String> failedBarriers) {

        public boolean isAllowed() {
            return status == Status.ALLOWED;
        }

        public boolean isBlocked() {
            return status == Status.BLOCKED;
        }

        public String describe() {
            return switch (status) {
                case NOT_REQUESTED -> "guardas de fixtures: no solicitadas (flag off), nada que sembrar";
                case ALLOWED -> "guardas de fixtures: las 4 barreras pasan";
                case BLOCKED -> "guardas de fixtures: BLOQUEADO por " + String.join(" | ", failedBarriers);
            };
        }
    }

    /**
     * Evalua las cuatro barreras.
     *
     * @param activeProfiles   perfiles de Spring activos
     * @param properties       configuracion de las guardas
     * @param actualDatabase   nombre real de la base de datos conectada
     * @param tablesInDatabase tablas existentes en el esquema {@code public}
     */
    public static Verdict evaluate(Set<String> activeProfiles,
                                   FixturesProperties properties,
                                   String actualDatabase,
                                   Set<String> tablesInDatabase) {

        List<String> failures = new ArrayList<>();
        Set<String> profiles = activeProfiles == null ? Set.of() : activeProfiles;

        // Barrera 1 - perfil de Spring.
        boolean profileAllowed = profiles.stream()
                .map(p -> p.toLowerCase(Locale.ROOT))
                .anyMatch(p -> properties.allowedProfiles().contains(p));
        if (!profileAllowed) {
            failures.add("perfil activo " + profiles + " no esta en " + properties.allowedProfiles());
        }

        // Barrera 2 - bandera explicita de entorno.
        if (!properties.enabled()) {
            failures.add("dentalclinic.fixtures.enabled=false");
        }

        // Barrera 3 - verificacion de base de datos.
        String database = actualDatabase == null ? "" : actualDatabase.toLowerCase(Locale.ROOT);
        if (database.isEmpty()) {
            failures.add("no se pudo determinar el nombre de la base de datos conectada");
        } else if (properties.forbiddenDatabases().contains(database)) {
            failures.add("la base de datos '" + actualDatabase + "' esta en la lista prohibida");
        } else if (properties.expectedDatabase() == null
                || !properties.expectedDatabase().equalsIgnoreCase(actualDatabase)) {
            failures.add("la base '" + actualDatabase + "' no coincide con la esperada '"
                    + properties.expectedDatabase() + "'");
        }

        // Barrera 4 - base vacia de tablas de negocio.
        Set<String> nonEmpty = businessTables(tablesInDatabase);
        if (properties.requireEmptyDatabase() && !nonEmpty.isEmpty()) {
            failures.add("la base ya contiene tablas de negocio: " + nonEmpty);
        }

        if (!properties.enabled()) {
            return new Verdict(Status.NOT_REQUESTED, List.copyOf(failures));
        }
        return failures.isEmpty()
                ? new Verdict(Status.ALLOWED, List.of())
                : new Verdict(Status.BLOCKED, List.copyOf(failures));
    }

    /** Filtra las tablas y deja solo las que no son infraestructura tecnica. */
    public static Set<String> businessTables(Set<String> tablesInDatabase) {
        if (tablesInDatabase == null) {
            return Set.of();
        }
        return tablesInDatabase.stream()
                .filter(table -> !TECHNICAL_TABLES.contains(table.toLowerCase(Locale.ROOT)))
                .collect(Collectors.toUnmodifiableSet());
    }
}
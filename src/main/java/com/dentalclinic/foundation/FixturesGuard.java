package com.dentalclinic.foundation;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Verificacion de las guardas de fixtures en el arranque (Architecture.md 13.3).
 *
 * <p>Barrera 1 (aislamiento por perfil) la aplica el {@code @Profile}: esta clase
 * solo se instancia bajo los perfiles {@code dev} y {@code demo}. En el perfil por
 * defecto y en {@code prod} no existe, de modo que ninguna ruta de fixtures puede
 * ejecutarse ahi (Agents.md Regla 13 y Regla 14).
 *
 * <p>Barreras 2, 3 y 4 (bandera explicita, verificacion de base de datos y base
 * vacia) se evaluan aqui. El proceso se detiene solo cuando alguien pidio fixtures
 * y una barrera fallo; con la bandera apagada no hay nada que sembrar.
 *
 * <p>Este componente no crea datos. Comprueba condiciones y, como maximo, detiene
 * el arranque (Agents.md Regla 11).
 */
@Component
@Profile({ "dev", "demo" })
public class FixturesGuard implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(FixturesGuard.class);

    private final Environment environment;
    private final DataSource dataSource;
    private final FixturesProperties properties;

    public FixturesGuard(Environment environment, DataSource dataSource, FixturesProperties properties) {
        this.environment = environment;
        this.dataSource = dataSource;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        Set<String> profiles = new java.util.LinkedHashSet<>(java.util.Arrays.asList(environment.getActiveProfiles()));

        // Barrera 2 primero: sin bandera explicita no se ha solicitado sembrar nada,
        // asi que no se abre conexion ni se inspecciona el esquema.
        if (!properties.enabled()) {
            log.info("guardas de fixtures: no solicitadas (dentalclinic.fixtures.enabled=false). "
                    + "No se siembra nada. [perfil={}]", profiles);
            return;
        }

        String database = currentDatabaseName();
        Set<String> tables = currentTables();

        FixturesPolicy.Verdict verdict = FixturesPolicy.evaluate(profiles, properties, database, tables);
        log.info("{} [perfil={} base={} tablas={}]", verdict.describe(), profiles, database, tables);

        if (verdict.isBlocked()) {
            throw new FixturesBlockedException(
                    "Se bloqueo la ejecucion de fixtures. " + verdict.describe());
        }
    }

    private String currentDatabaseName() {
        try (Connection connection = dataSource.getConnection()) {
            return databaseNameFrom(connection.getMetaData().getURL());
        } catch (SQLException e) {
            throw new FixturesBlockedException(
                    "No se pudo verificar la base de datos para las guardas de fixtures: " + e.getMessage());
        }
    }

    /**
     * Extrae el nombre de la base del JDBC url. Descarta parametros de query,
     * el esquema opcional y las barras sobrantes, de modo que
     * {@code jdbc:postgresql://host:5432/dentalclinic_dev?x=1} y
     * {@code jdbc:postgresql://host:5432/dentalclinic_dev/} devuelvan ambos
     * {@code dentalclinic_dev}, y que un url sin base devuelva {@code null}
     * en lugar del numero de puerto.
     */
    static String databaseNameFrom(String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        String path = jdbcUrl;
        int queryStart = path.indexOf('?');
        if (queryStart >= 0) {
            path = path.substring(0, queryStart);
        }
        int authorityStart = path.indexOf("://");
        authorityStart = authorityStart >= 0 ? authorityStart + 3 : 0;
        int firstSlash = path.indexOf('/', authorityStart);
        if (firstSlash < 0) {
            return null;
        }
        String database = path.substring(firstSlash + 1);
        int schemaSeparator = database.indexOf('/');
        if (schemaSeparator >= 0) {
            database = database.substring(0, schemaSeparator);
        }
        return database.isEmpty() ? null : database;
    }

    private Set<String> currentTables() {
        Set<String> tables = new LinkedHashSet<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT table_name FROM information_schema.tables "
                             + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE' "
                             + "ORDER BY table_name")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
            return tables;
        } catch (SQLException e) {
            throw new FixturesBlockedException(
                    "No se pudo inspeccionar el esquema para las guardas de fixtures: " + e.getMessage());
        }
    }
}
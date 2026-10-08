package com.dentalclinic.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Verificacion de las guardas contra un PostgreSQL real (Architecture.md 13.3).
 *
 * <p>No hay seeder en Fase 1: lo que se prueba es que el mecanismo de bloqueo
 * existe y detiene el arranque cuando alguien pide fixtures contra una base que
 * no debe sembrarse.
 */
class FixturesGuardIntegrationTest extends PostgresTestSupport {

    private DataSource dataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    private FixturesProperties properties(boolean enabled, String expectedDatabase) {
        return new FixturesProperties(
                enabled,
                Set.of("dev", "demo"),
                expectedDatabase,
                true,
                Set.of("dental", "postgres", "production", "prod"));
    }

    @Test
    void conBaseQueNoCoincide_abortaElArranque() {
        DataSource ds = dataSource();
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");

        FixturesGuard guard = new FixturesGuard(
                env, ds, properties(true, "otra_base_de_datos"));

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(FixturesBlockedException.class)
                .hasMessageContaining("BLOQUEADO");
    }

    @Test
    void conPerfilNoAutorizado_abortaElArranque() {
        DataSource ds = dataSource();
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        FixturesGuard guard = new FixturesGuard(
                env, ds, properties(true, POSTGRES.getDatabaseName()));

        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(FixturesBlockedException.class);
    }

    @Test
    void conTodasLasBarrerasSuperadas_noAborta() {
        DataSource ds = dataSource();
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");

        FixturesGuard guard = new FixturesGuard(
                env, ds, properties(true, POSTGRES.getDatabaseName()));

        assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void conBanderaApagada_noAbreConexionNiBloquea() {
        DataSource ds = new HikariDataSource() {
            @Override
            public java.sql.Connection getConnection() {
                throw new IllegalStateException("No deberia abrirse conexion con la bandera apagada");
            }
        };
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");

        FixturesGuard guard = new FixturesGuard(env, ds, properties(false, "lo_que_sea"));

        assertThatCode(guard::afterPropertiesSet).doesNotThrowAnyException();
        assertThat(FixturesPolicy.Status.NOT_REQUESTED).isNotNull();
    }
}
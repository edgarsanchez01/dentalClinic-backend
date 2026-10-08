package com.dentalclinic.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * El runtime de produccion arranca vacio (Architecture.md 13.1, Agents.md Regla 11
 * y Regla 12, criterio de salida de Fase 1).
 *
 * <p>Se levanta el perfil {@code prod} contra un PostgreSQL efimero y se comprueba
 * que el arranque no altera el esquema ni crea datos de negocio de ningun tipo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("prod")
class ProductionRuntimeCreatesNoDataTest extends PostgresTestSupport {

    @Autowired
    private DataSource dataSource;

    @Value("${spring.jpa.hibernate.ddl-auto}")
    private String ddlAuto;

    @Value("${dentalclinic.fixtures.enabled}")
    private boolean fixturesEnabled;

    @Test
    void produccionUsaDdlNone() {
        assertThat(ddlAuto)
                .as("produccion nunca usa ddl-auto=update ni ningun valor que altere el esquema")
                .isEqualTo("none");
    }

    @Test
    void fixturesEstanDesactivadas() {
        assertThat(fixturesEnabled).isFalse();
    }

    @Test
    void arrancarNoCreaNingunaTabla() throws Exception {
        assertThat(businessTables())
                .as("el arranque en produccion no debe crear tablas de negocio")
                .isEmpty();
    }

    private Set<String> businessTables() throws Exception {
        Set<String> tables = new LinkedHashSet<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT table_name FROM information_schema.tables "
                             + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return FixturesPolicy.businessTables(tables);
    }
}
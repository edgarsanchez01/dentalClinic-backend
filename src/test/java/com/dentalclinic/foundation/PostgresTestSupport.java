package com.dentalclinic.foundation;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Contenedor PostgreSQL real compartido por los tests de integracion (Architecture.md 5.1).
 *
 * <p>Nunca se conecta a la base de datos {@code dental} (Agents.md Regla 18).
 * El contenedor es efimero y se destruye al terminar la JVM.
 */
@Testcontainers
abstract class PostgresTestSupport {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("dentalclinic_it")
            .withUsername("dentalclinic")
            .withPassword("dentalclinic");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }
}
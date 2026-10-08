package com.dentalclinic.foundation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DatabaseNameFromTest {

    @Test
    void urlSimple() {
        assertThat(FixturesGuard.databaseNameFrom("jdbc:postgresql://localhost:5432/dentalclinic_dev"))
                .isEqualTo("dentalclinic_dev");
    }

    @Test
    void urlConParametros() {
        assertThat(FixturesGuard.databaseNameFrom(
                "jdbc:postgresql://localhost:5432/dentalclinic_it?loggerLevel=OFF"))
                .isEqualTo("dentalclinic_it");
    }

    @Test
    void urlConParametrosYBarraFinal() {
        assertThat(FixturesGuard.databaseNameFrom(
                "jdbc:postgresql://h:5432/dentalclinic_it/?ApplicationName=x"))
                .isEqualTo("dentalclinic_it");
    }

    @Test
    void urlSinBase() {
        assertThat(FixturesGuard.databaseNameFrom("jdbc:postgresql://localhost:5432/")).isNull();
    }

    @Test
    void urlNula() {
        assertThat(FixturesGuard.databaseNameFrom(null)).isNull();
    }
}
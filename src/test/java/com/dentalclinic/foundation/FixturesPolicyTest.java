package com.dentalclinic.foundation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;

import org.junit.jupiter.api.Test;

class FixturesPolicyTest {

    private final FixturesProperties properties = new FixturesProperties(
            true,
            Set.of("dev", "demo"),
            "dentalclinic_dev",
            true,
            Set.of("dental", "postgres", "production", "prod"));

    @Test
    void cuandoPerfilNoAutorizado_yBanderaActiva_bloquea() {
        FixturesPolicy.Verdict verdict = FixturesPolicy.evaluate(
                Set.of("default"),
                properties,
                "dentalclinic_dev",
                Set.of());
        assertThat(verdict.isBlocked()).isTrue();
        assertThat(verdict.failedBarriers()).isNotEmpty();
    }

    @Test
    void cuandoBaseProhibida_bloquea() {
        FixturesPolicy.Verdict verdict = FixturesPolicy.evaluate(
                Set.of("dev"),
                properties,
                "postgres",
                Set.of());
        assertThat(verdict.isBlocked()).isTrue();
    }

    @Test
    void cuandoBaseNoCoincide_bloquea() {
        FixturesPolicy.Verdict verdict = FixturesPolicy.evaluate(
                Set.of("dev"),
                properties,
                "dentalclinic_staging",
                Set.of());
        assertThat(verdict.isBlocked()).isTrue();
    }

    @Test
    void cuandoBaseNoVacia_bloquea() {
        FixturesPolicy.Verdict verdict = FixturesPolicy.evaluate(
                Set.of("dev"),
                properties,
                "dentalclinic_dev",
                Set.of("clinic", "user"));
        assertThat(verdict.isBlocked()).isTrue();
    }

    @Test
    void cuandoTodoPasa_yFlagOn_permite() {
        FixturesPolicy.Verdict verdict = FixturesPolicy.evaluate(
                Set.of("dev"),
                properties,
                "dentalclinic_dev",
                Set.of());
        assertThat(verdict.isAllowed()).isTrue();
    }

    @Test
    void cuandoFlagOff_noSolicita() {
        FixturesProperties p = new FixturesProperties(
                false,
                Set.of("dev"),
                "dentalclinic_dev",
                true,
                Set.of());
        FixturesPolicy.Verdict verdict = FixturesPolicy.evaluate(
                Set.of("dev"),
                p,
                "dentalclinic_dev",
                Set.of());
        assertThat(verdict.status()).isEqualTo(FixturesPolicy.Status.NOT_REQUESTED);
    }

    @Test
    void tablasTecnicasNoSonNegocio() {
        assertThat(FixturesPolicy.businessTables(Set.of("app_schema_version", "clinic")))
                .containsExactly("clinic");
    }
}
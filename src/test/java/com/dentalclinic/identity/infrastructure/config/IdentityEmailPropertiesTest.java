package com.dentalclinic.identity.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdentityEmailPropertiesTest {

    @Test
    void porDefectoNoProgramaEnvios() {
        var properties = new IdentityEmailProperties(false, 0, 0, null, 0, null, null, null, null);
        assertThat(properties.dispatchEnabled()).isFalse();
        assertThat(properties.lease()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.maxAttempts()).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void leaseNoPositivoSeRechazaParaNoReclamarDuranteElEnvio(long millis) {
        assertThatThrownBy(() -> new IdentityEmailProperties(true, 20, 4, null, 5, null,
                Duration.ofMillis(millis), null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.5, Double.NaN, Double.POSITIVE_INFINITY})
    void multiplicadorInvalidoNoProduceReintentosInmediatos(double multiplier) {
        assertThatThrownBy(() -> new IdentityEmailProperties(true, 20, 4, null, multiplier, null,
                null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}

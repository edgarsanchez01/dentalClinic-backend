package com.dentalclinic.identity.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Validación de la configuración de enlaces de identidad (F8B/T1).
 *
 * <p>Lo decisivo: nada se exige mientras el canal esté apagado y, al encenderlo, la URL debe ser
 * segura (HTTPS salvo localhost) y la clave dedicada debe tener al menos 32 caracteres.
 */
class IdentityLinkPropertiesTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void sinConfiguracion_EsValidoYDeshabilitado() {
        IdentityLinkProperties props = new IdentityLinkProperties(null, null, false);
        assertThat(props.publicBaseUrl()).isEmpty();
        assertThat(props.linkSecret()).isEmpty();
        assertThat(props.hasLinkSecret()).isFalse();
        assertThat(props.configured()).isFalse();
    }

    @Test
    void urlHttps_SeNormalizaSinBarraFinal() {
        IdentityLinkProperties props = new IdentityLinkProperties("https://clinic.example.test/", SECRET, true);
        assertThat(props.publicBaseUrl()).isEqualTo("https://clinic.example.test");
        assertThat(props.configured()).isTrue();
    }

    @Test
    void urlHttpLocalhost_EsValida() {
        assertThat(new IdentityLinkProperties("http://localhost:5173", SECRET, true).publicBaseUrl())
                .isEqualTo("http://localhost:5173");
        assertThat(new IdentityLinkProperties("http://127.0.0.1:5173", SECRET, true).configured()).isTrue();
    }

    @Test
    void urlHttpNoLocalhost_SeRechaza() {
        assertThatThrownBy(() -> new IdentityLinkProperties("http://clinic.example.test", SECRET, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void urlConQueryFragmentOUserInfo_SeRechaza() {
        assertThatThrownBy(() -> new IdentityLinkProperties("https://clinic.example.test/?x=1", SECRET, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdentityLinkProperties("https://clinic.example.test/#f", SECRET, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdentityLinkProperties("https://user@clinic.example.test", SECRET, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void habilitadoSinUrl_Falla() {
        assertThatThrownBy(() -> new IdentityLinkProperties("", SECRET, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IDENTITY_PUBLIC_BASE_URL");
    }

    @Test
    void habilitadoSinSecreto_Falla() {
        assertThatThrownBy(() -> new IdentityLinkProperties("https://clinic.example.test", "", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IDENTITY_LINK_SECRET");
    }

    @Test
    void habilitadoConSecretoCorto_Falla() {
        assertThatThrownBy(() -> new IdentityLinkProperties("https://clinic.example.test", "corto", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void secretoCortoAunqueDeshabilitado_Falla() {
        assertThatThrownBy(() -> new IdentityLinkProperties("", "corto", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deshabilitadoConUrlValida_NoEstaConfigurado() {
        IdentityLinkProperties props = new IdentityLinkProperties("https://clinic.example.test", SECRET, false);
        assertThat(props.hasLinkSecret()).isTrue();
        assertThat(props.configured()).isFalse();
    }

    @Test
    void toString_NoExponeLaClave() {
        String rendered = new IdentityLinkProperties("https://clinic.example.test", SECRET, true).toString();
        assertThat(rendered).doesNotContain(SECRET).contains("<redacted>");
    }

    @Test
    void urlMalformadaNoFiltraLaEntradaEnLaCadenaDeExcepciones() {
        assertThatThrownBy(() -> new IdentityLinkProperties(
                "https://clinic.example.test/invalid path?token=sensitive", SECRET, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("La URL pública de identidad no es válida.")
                .hasNoCause();
    }
}

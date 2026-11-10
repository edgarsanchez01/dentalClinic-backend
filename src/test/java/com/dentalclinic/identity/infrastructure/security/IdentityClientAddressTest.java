package com.dentalclinic.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import com.dentalclinic.identity.infrastructure.config.IdentityAbuseProperties;

class IdentityClientAddressTest {
    private IdentityClientAddress resolver(List<String> proxies) {
        return new IdentityClientAddress(new IdentityAbuseProperties(null, null, 0, null, null, 0,
                null, 0, 0, 0, proxies));
    }

    @Test
    void sinProxyConfiableIgnoraTodasLasCabeceras() {
        var request = request("192.0.2.1", "198.51.100.1");
        request.addHeader("Forwarded", "for=198.51.100.2");
        request.addHeader("X-Real-IP", "198.51.100.3");
        assertThat(resolver(List.of()).resolve(request)).isEqualTo("192.0.2.1");
    }

    @Test
    void recorreCadenaDesdeLaDerechaYDetieneEnPrimerSaltoNoConfiable() {
        var resolver = resolver(List.of("10.0.0.0/8", "2001:db8:1::/48"));
        assertThat(resolver.resolve(request("10.0.0.2", "203.0.113.9, 198.51.100.8, 10.0.0.3")))
                .isEqualTo("198.51.100.8");
        assertThat(resolver.resolve(request("2001:db8:1::2", "2001:db8:2::1")))
                .isEqualTo("2001:db8:2:0:0:0:0:1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"host.example.test", "dead.beef", "999.1.1.1", "unknown", "198.51.100.2,", "198.51.100.2:80", "for=198.51.100.2"})
    void cadenaMalformadaVuelveAlPeerSinDns(String xff) {
        assertThat(resolver(List.of("10.0.0.0/8")).resolve(request("10.0.0.2", xff))).isEqualTo("10.0.0.2");
    }

    @Test
    void cabecerasDesmesuradasNoAgotanRecursos() {
        var resolver = resolver(List.of("10.0.0.0/8"));
        assertThat(resolver.resolve(request("10.0.0.2", "1".repeat(1025)))).isEqualTo("10.0.0.2");
        assertThat(resolver.resolve(request("10.0.0.2", "10.0.0.3,".repeat(17)))).isEqualTo("10.0.0.2");
    }

    @Test
    void multiplesCamposXffSeIgnoran() {
        var request = request("10.0.0.2", "198.51.100.1");
        request.addHeader("X-Forwarded-For", "198.51.100.2");
        assertThat(resolver(List.of("10.0.0.0/8")).resolve(request)).isEqualTo("10.0.0.2");
    }

    @Test
    void proxyHostnameSeRechazaEnConfiguracion() {
        assertThatThrownBy(() -> resolver(List.of("proxy.example.test"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver(List.of("dead.beef/24"))).isInstanceOf(IllegalArgumentException.class);
    }

    private MockHttpServletRequest request(String peer, String xff) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        request.addHeader("X-Forwarded-For", xff);
        return request;
    }
}

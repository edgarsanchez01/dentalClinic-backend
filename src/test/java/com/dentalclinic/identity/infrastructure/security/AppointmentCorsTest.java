package com.dentalclinic.identity.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.CorsFilter;

class AppointmentCorsTest {

    private static final String ORIGIN = "https://clinic.example";
    private MockMvc mockMvc;
    private SecurityConfig securityConfig;

    @BeforeEach
    void setUp() {
        var properties = new SecurityProperties(null, null, null, null, null,
                new SecurityProperties.Cors(List.of(ORIGIN)));
        securityConfig = new SecurityConfig(properties);
        mockMvc = MockMvcBuilders.standaloneSetup(new Object())
                .addFilters(new CorsFilter(securityConfig.corsConfigurationSource())).build();
    }

    @Test
    void preflightPermiteIdempotencyKeyYLasCabecerasExistentes() throws Exception {
        mockMvc.perform(options("/api/appointments")
                        .header("Origin", ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers",
                                "Authorization, Content-Type, X-XSRF-TOKEN, Idempotency-Key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Headers",
                        "Authorization, Content-Type, X-XSRF-TOKEN, Idempotency-Key"))
                .andExpect(header().string("Access-Control-Allow-Methods",
                        "GET,POST,PATCH,PUT,DELETE,OPTIONS"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(header().string("Access-Control-Max-Age", "3600"));
    }

    @Test
    void preflightRechazaOrigenNoConfigurado() throws Exception {
        mockMvc.perform(options("/api/appointments")
                        .header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Idempotency-Key"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD", "TRACE", "CONNECT"})
    void preflightRechazaMetodosNoPermitidos(String method) throws Exception {
        mockMvc.perform(options("/api/appointments").header("Origin", ORIGIN)
                        .header("Access-Control-Request-Method", method))
                .andExpect(status().isForbidden());
    }

    @Test
    void preflightRechazaCabecerasNoPermitidas() throws Exception {
        mockMvc.perform(options("/api/appointments").header("Origin", ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "X-Untrusted"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Headers"));
    }

    @Test
    void preflightPermiteLaCabeceraSinDistinguirMayusculas() throws Exception {
        mockMvc.perform(options("/api/appointments").header("Origin", ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Headers", "idempotency-key"));
    }

    @Test
    void conservaAllowlistCredencialesExposicionYAlcance() {
        var source = securityConfig.corsConfigurationSource();
        var configuration = source.getCorsConfiguration(
                new MockHttpServletRequest("OPTIONS", "/api/appointments"));
        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins()).containsExactly(ORIGIN);
        assertThat(configuration.getAllowedOriginPatterns()).isNullOrEmpty();
        assertThat(configuration.getAllowedMethods())
                .containsExactly("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS");
        assertThat(configuration.getAllowedHeaders())
                .containsExactly("Authorization", "Content-Type", "X-XSRF-TOKEN", "Idempotency-Key");
        assertThat(configuration.getExposedHeaders()).containsExactly("Set-Cookie");
        assertThat(configuration.getAllowCredentials()).isTrue();
        assertThat(configuration.getMaxAge()).isEqualTo(3600L);
        assertThat(source.getCorsConfiguration(
                new MockHttpServletRequest("OPTIONS", "/actuator/health"))).isNull();
    }
}

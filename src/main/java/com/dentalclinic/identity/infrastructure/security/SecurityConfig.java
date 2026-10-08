package com.dentalclinic.identity.infrastructure.security;

import java.util.List;
import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.http.Cookie;

/**
 * Cadena de seguridad HTTP de Fase 2.
 *
 * <p>La configuración es <strong>stateless</strong> (§7.1): sin {@code HttpSession}, sin
 * {@code JSESSIONID}, sin autenticación por formulario. El access token viaja en la cabecera
 * {@code Authorization} y el refresh token en una cookie {@code HttpOnly} que el JavaScript no
 * puede leer.
 *
 * <p>La política de rutas se deriva de la matriz §7.4 mediante
 * {@link EndpointAccessFilter}, de modo que la matriz no está duplicada: está escrita una vez en
 * {@code EndpointGroup} y consumida tanto por el filtro como por los tests de matriz.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Rutas públicas que <strong>no</strong> dependen de cookie y por tanto quedan fuera de CSRF.
     *
     * <p>Son las que inventan o consumen una identidad sin usar la sesión existente: login,
     * activación, forgotten y reset (§14.1).
     *
     * <p><strong>{@code POST /api/auth/register}</strong> implementado en Fase 4 (Agents.md §3.1)
     * es público (PRD §24.4, Architecture §7.3) y no depende de cookie. El backend crea en una sola
     * transacción la cuenta {@code User(PATIENT, active=true)} y su {@code Patient}; el rol nunca
     * viene del cuerpo.
     */
    private static final String[] PUBLIC_PATHS_WITHOUT_COOKIE = {
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/activate",
            "/api/auth/password/forgot",
            "/api/auth/password/reset",
            // Fase 3: el onboarding y su estado son públicos por definición (la instalación todavía
            // no tiene usuarios). No dependen de cookie, así que quedan fuera de CSRF. La lógica de
            // "solo una vez" (403/409) vive en OnboardingService, no en la allowlist.
            "/api/onboarding",
            "/api/onboarding/status",
            // Fase 5: el horario público de la clínica y sus dentistas es una allowlist pública
            // (Arquitectura §7.3, RF-SC-08). No depende de cookie.
            "/api/public/schedule"
    };

    /**
     * Rutas públicas que <strong>sí</strong> dependen de cookie y por tanto exigen CSRF.
     *
     * <p>Ambas leen la cookie del refresh token, y un sitio tercero podría hacer que el navegador
     * del usuario emitiera la petición por él. §7.1 lo dice sin ambigüedad: "CSRF es obligatorio en
     * las operaciones que dependen de cookie". Se mantienen fuera de la matriz de rol porque no
     * requieren access token: son precisamente el camino de recuperación cuando este ya expiró.
     */
    private static final String[] PUBLIC_PATHS_WITH_COOKIE = {
            "/api/auth/refresh",
            "/api/auth/logout"
    };

    private final SecurityProperties properties;

    public SecurityConfig(SecurityProperties properties) {
        this.properties = properties;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationFilter jwtAuthenticationFilter,
                                           EndpointAccessFilter endpointAccessFilter,
                                           JsonAuthenticationEntryPoint authenticationEntryPoint,
                                           JsonAccessDeniedHandler accessDeniedHandler,
                                           CsrfCookieFilter csrfCookieFilter) throws Exception {

        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository())
                        .csrfTokenRequestHandler(csrfRequestHandler)
                        .ignoringRequestMatchers(PUBLIC_PATHS_WITHOUT_COOKIE)
                        .requireCsrfProtectionMatcher(cookieDependentRequestMatcher()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(PUBLIC_PATHS_WITHOUT_COOKIE).permitAll();
                    auth.requestMatchers(PUBLIC_PATHS_WITH_COOKIE).permitAll();
                    // Sondeo de vida del proceso: lo consultan Docker, Kubernetes y el
                    // balanceador sin credenciales (Architecture.md 20).
                    auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll();
                    auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    auth.anyRequest().authenticated();
                })
                // AFTER SecurityContextHolderFilter, NO BEFORE: ese filtro gestiona el contexto de
                // la peticion y, si el nuestro corre antes, su carga de contexto (vacia, sin sesion)
                // sobrescribe la autenticacion que acabamos de establecer. Esta es la ordenacion
                // que recomienda Spring Security 6 para filtros que pueblan el contexto.
                .addFilterAfter(jwtAuthenticationFilter,
                        org.springframework.security.web.context.SecurityContextHolderFilter.class)
                .addFilterAfter(endpointAccessFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(csrfCookieFilter, EndpointAccessFilter.class)
                .build();
    }

    /**
     * Token CSRF en cookie legible por el frontend, que lo reenvía en
     * {@code X-XSRF-TOKEN}. No es {@code HttpOnly} por diseño: el navegador tiene que poder leerlo
     * para adjuntarlo a las peticiones que dependen de cookie.
     */
    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    /**
     * CSRF solo se exige cuando la petición trae la cookie del refresh token.
     *
     * <p>CSRF protege frente a peticiones que el navegador emite <em>automáticamente</em> con
     * credenciales de cookie. Una petición sin esa cookie no puede autenticarse por cookie, así que
     * no hay nada que falsificar: exigirle un token CSRF solo lograría convertir en 403 una
     * petición anónima que el contrato describe como 401 (refresh sin sesión), o el registro
     * público (Fase 4) que no usa cookie. Con este matcher, la ausencia de cookie cae en la
     * autorización normal —401— y la presencia de cookie exige el token.
     */
    private RequestMatcher cookieDependentRequestMatcher() {
        Set<String> safeMethods = Set.of("GET", "HEAD", "TRACE", "OPTIONS");
        String refreshCookieName = properties.refreshCookie().name();
        return request -> {
            if (safeMethods.contains(request.getMethod())) {
                return false;
            }
            Cookie[] cookies = request.getCookies();
            if (cookies == null) {
                return false;
            }
            for (Cookie cookie : cookies) {
                if (refreshCookieName.equals(cookie.getName())) {
                    return true;
                }
            }
            return false;
        };
    }

    /**
     * CORS con orígenes explícitos (§7.9): nunca comodín. En dev el frontend corre en otro puerto
     * que el backend, así que el origen exacto debe estar permitido.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.cors().allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-XSRF-TOKEN"));
        configuration.setExposedHeaders(List.of("Set-Cookie"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
package com.dentalclinic.identity.infrastructure.security;

import java.io.IOException;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Aplica la matriz de autorización §7.4 a cada ruta.
 *
 * <p>Existe porque una matriz declarativa sobre el rol no puede expresar "solo lectura"
 * ({@code GET}) sin lógica. Aquí se resuelve grupo + método + rol contra
 * {@link EndpointAccessPolicy}, y se distinguen los casos que §7.4 separa:
 *
 * <ul>
 *   <li>ruta fuera de la matriz, o método seguro con acceso de lectura → deja pasar; la
 *       autorización fina la aplican los {@code @PreAuthorize} de los controllers</li>
 *   <li>escritura sobre un grupo con solo lectura, o cualquier denegación por rol → <strong>403</strong></li>
 * </ul>
 *
 * <p>La pertenencia a recursos propios ({@code SELF_READ}, {@code SELF_READ_WRITE}) <strong>no</strong>
 * se decide aquí: se verifica en el servicio que carga el recurso, porque requiere consultar la
 * base de datos. Aquí solo se marca
 * {@link EndpointAccessPolicy.EndpointDecision#ownershipCheckRequired()} para que el servicio lo
 * sepa.
 *
 * <p><strong>Fuera de la matriz se deniega.</strong> Una ruta de negocio bajo {@code /api} que no
 * encaje en ningún grupo devuelve 403 en vez de dejar pasar. Es el criterio de fallo cerrado: si
 * alguien añade un endpoint nuevo y olvida decidir su autorización, el sistema se cierra por
 * defecto en lugar de abrirse.
 *
 * <p><strong>Dos exclusiones, ambas justificadas.</strong>
 *
 * <ul>
 *   <li>{@code /api/auth/**}: la matriz §7.4 no cubre el módulo de identidad, porque su
 *       autorización no es "un rol sobre un grupo" sino la regla explícita de §7.3 — allowlist
 *       pública cerrada y todo lo demás autenticado. Aplicar aquí un fallo cerrado por grupo
 *       denegaría {@code /api/auth/me} a un usuario correctamente autenticado.</li>
 *   <li>{@code /api/onboarding/**}: es una allowlist pública condicionada (§7.3). Su autorización
 *       es el propio estado de la instalación, que el servicio resuelve (403 si ya está
 *       configurada, 409 si se pierde la carrera), no un rol sobre un grupo. Dejarlo caer en el
 *       fallo cerrado produciría 403 siempre, sin distinguir los dos casos.</li>
 *   <li>{@code /api/public/schedule}: horario público de la landing (F5, §7.3). Su autorización es
 *       una allowlist explícita <strong>de ruta exacta</strong> en la cadena de Spring Security, no
 *       un rol sobre un grupo de la matriz §7.4. Aplicar aquí el fallo cerrado denegaría
 *       {@code /api/public/schedule} a un usuario autenticado que lo consulte desde el área
 *       privada. La exención es de una única ruta: <strong>no</strong> se abre todo el namespace
 *       {@code /api/public/**}, de modo que cualquier endpoint futuro conserve el fallo cerrado
 *       (un endpoint público nuevo debe declararse de forma explícita, igual que en
 *       {@code SecurityConfig}).</li>
 *   <li>Lo que no es de la API ({@code /actuator}, {@code /error}): lo gobierna la cadena de
 *       Spring Security.</li>
 * </ul>
 */
@Component
public class EndpointAccessFilter extends OncePerRequestFilter {

    private final EndpointAccessPolicy accessPolicy;
    private final JsonAccessDeniedHandler accessDeniedHandler;

    public EndpointAccessFilter(EndpointAccessPolicy accessPolicy,
                                JsonAccessDeniedHandler accessDeniedHandler) {
        this.accessPolicy = accessPolicy;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    private static final String API_PREFIX = "/api";
    private static final String AUTH_PREFIX = "/api/auth";
    private static final String ONBOARDING_PREFIX = "/api/onboarding";
    /**
     * Ruta pública exacta de F5. Deliberadamente no es un prefijo: el namespace {@code /api/public}
     * no queda exento de la matriz, solo esta ruta concreta (aprobada como pública en §7.3).
     */
    private static final String PUBLIC_SCHEDULE_PATH = "/api/public/schedule";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        AuthenticatedUser principal = CurrentUser.get();
        if (principal != null && !isPermitted(request, principal)) {
            accessDeniedHandler.handle(request, response, new AccessDeniedException("Matriz §7.4"));
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * @return {@code true} si la petición puede continuar
     */
    private boolean isPermitted(HttpServletRequest request, AuthenticatedUser principal) {
        String path = requestPath(request);
        if (!path.startsWith(API_PREFIX)
                || path.startsWith(AUTH_PREFIX)
                || path.startsWith(ONBOARDING_PREFIX)
                || path.equals(PUBLIC_SCHEDULE_PATH)) {
            return true;
        }
        return accessPolicy.decide(path, request.getMethod(), principal.role())
                .map(EndpointAccessPolicy.EndpointDecision::permitted)
                // Sin grupo en la matriz, la ruta de API se deniega (fallo cerrado).
                .orElse(false);
    }

    /** Ruta de la petición sin context path, con el patrón {@code /**} de §7.4. */
    private String requestPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        if (uri.length() > 1 && uri.endsWith("/")) {
            uri = uri.substring(0, uri.length() - 1);
        }
        return uri;
    }
}
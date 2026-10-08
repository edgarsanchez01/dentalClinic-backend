package com.dentalclinic.identity.infrastructure.security;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.dentalclinic.identity.domain.User;
import com.dentalclinic.identity.domain.exception.InvalidCredentialsException;
import com.dentalclinic.identity.infrastructure.repository.UserRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Autenticación por access token JWT.
 *
 * <p>Lee la cabecera {@code Authorization: Bearer} y, si el token es válido, construye el
 * principal de la petición.
 *
 * <p><strong>Por qué vuelve a consultar la base de datos en cada petición</strong> (§7.1,
 * RF-IA-14): el access token es stateless y por diseño no puede revocado antes de expirar. La
 * única forma de que "desactivar un usuario corta el acceso en la siguiente petición"
 * (Architecture.md §5.2) sea cierta es comprobar contra la base de datos que el usuario sigue
 * existiendo y con {@code active = true}, en lugar de confiar solo en los claims del token. Es un
 * coste deliberado: una consulta por clave primaria por petición autenticada.
 *
 * <p>Si el token falta, está caducado o alterado, el filtro <strong>no</strong> autentica y deja
 * que la cadena continúe: la respuesta la produce el punto de entrada de autenticación, que
 * devuelve 401 con el código de error del contrato. Nunca 403 por token inválido, porque 403
 * significa "identidad válida pero sin permiso" (§7.4).
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService tokenService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtTokenService tokenService, UserRepository userRepository) {
        this.tokenService = tokenService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        extractBearerToken(request)
                .flatMap(tokenService::parse)
                .ifPresent(claims -> authenticate(request, claims.userId()));
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, Long userId) {
        Optional<User> found = userRepository.findById(userId);
        if (found.isEmpty() || !found.get().isActive()) {
            return;
        }
        User user = found.get();
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(user.getId(), user.getRole()), null, List.of());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private Optional<String> extractBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        return Optional.of(header.substring(BEARER_PREFIX.length()).trim());
    }
}
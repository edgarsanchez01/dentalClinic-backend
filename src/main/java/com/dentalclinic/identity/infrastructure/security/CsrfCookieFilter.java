package com.dentalclinic.identity.infrastructure.security;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Fuerza la materialización del token CSRF para que llegue a la cookie.
 *
 * <p>Con {@code CookieCsrfTokenRepository} el token es diferido: solo se escribe cuando alguien lo
 * pide. En una SPA eso deja al frontend sin forma de conocerlo en la primera carga, porque nadie
 * ha hecho todavía un GET que lo materialice. Este filtro lo resuelve: toca el token en cada
 * petición para que la cookie {@code XSRF-TOKEN} exista, y el frontend la reenvía en la cabecera
 * {@code X-XSRF-TOKEN} al llamar a las rutas que dependen de cookie.
 *
 * <p>Solo dos rutas dependen de cookie: {@code /api/auth/refresh} y {@code /api/auth/logout}.
 * El resto de peticiones autenticadas viajan con el access token en la cabecera
 * {@code Authorization}, que un navegador no adjunta solo, y por tanto no son vectores de CSRF.
 * §7.1 establece exactamente ese alcance.
 *
 * <p>La cookie no es {@code HttpOnly} porque el frontend tiene que poder leerla para copiarla en
 * la cabecera. Sigue siendo un valor de petición sin autorización por sí mismo.
 */
@Component
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CsrfTokenAccessor.materialize(request);
        filterChain.doFilter(request, response);
    }
}
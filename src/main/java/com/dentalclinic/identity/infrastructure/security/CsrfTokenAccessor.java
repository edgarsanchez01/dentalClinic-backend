package com.dentalclinic.identity.infrastructure.security;

import org.springframework.security.web.csrf.CsrfToken;

/**
 * Acceso al token CSRF diferido desde un filtro.
 *
 * <p>Con {@code CookieCsrfTokenRepository} y el {@code CsrfTokenRequestAttributeHandler} por
 * defecto, Spring expone el token como atributo de petición perezoso y no como bean. Este
 * envoltorio encapsula ese detalle: {@link CsrfCookieFilter} pide que se materialice sin conocer
 * el atributo concreto, y si en el futuro cambia la estrategia de repositorio, el cambio queda
 * aquí.
 */
final class CsrfTokenAccessor {

    private CsrfTokenAccessor() {
    }

    /** Fuerza la carga del token para que se escriba la cookie. */
    static void materialize(jakarta.servlet.http.HttpServletRequest request) {
        csrfToken(request).getToken();
    }

    static CsrfToken csrfToken(jakarta.servlet.http.HttpServletRequest request) {
        Object attribute = request.getAttribute(CsrfToken.class.getName());
        if (attribute instanceof CsrfToken token) {
            return token;
        }
        return request.getAttribute("_csrf") instanceof CsrfToken token ? token : new CsrfToken() {
            @Override
            public String getToken() {
                return "";
            }

            @Override
            public String getHeaderName() {
                return "X-XSRF-TOKEN";
            }

            @Override
            public String getParameterName() {
                return "_csrf";
            }
        };
    }
}
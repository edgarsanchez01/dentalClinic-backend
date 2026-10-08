package com.dentalclinic.identity.api.dto;

import java.time.Instant;

/**
 * Respuesta de {@code POST /api/auth/refresh}.
 *
 * <p>Devuelve únicamente los tokens: el perfil se resuelve con {@code GET /api/auth/me}, que es la
 * fuente autoritativa (§14.3) y evita que el refresh re-emita datos que el frontend ya cachea.
 * El access token viaja en el cuerpo —en memoria— y el refresh token rotado en la cookie.
 */
public record TokensResponse(
        String accessToken,
        String tokenType,
        Instant accessTokenExpiresAt) {

    public TokensResponse {
        tokenType = tokenType == null ? "Bearer" : tokenType;
    }
}

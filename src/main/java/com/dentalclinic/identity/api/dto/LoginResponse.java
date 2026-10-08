package com.dentalclinic.identity.api.dto;

import java.time.Instant;

/**
 * Respuesta de {@code POST /api/auth/login}.
 *
 * <p>RF-IA-10: el login "debe emitir un token de acceso stateless y devolver el perfil del
 * usuario". El access token viaja solo en el cuerpo —que el frontend mantiene <strong>en
 * memoria</strong>— y el refresh token viaja únicamente en la cookie {@code HttpOnly}, que este
 * cuerpo no repite en ningún campo.
 */
public record LoginResponse(
        String accessToken,
        String tokenType,
        Instant accessTokenExpiresAt,
        UserDto user) {

    public LoginResponse {
        tokenType = tokenType == null ? "Bearer" : tokenType;
    }
}

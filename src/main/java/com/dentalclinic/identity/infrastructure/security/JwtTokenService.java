package com.dentalclinic.identity.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import com.dentalclinic.identity.domain.Role;
import com.dentalclinic.identity.domain.User;

/**
 * Emisión y verificación del access token JWT.
 *
 * <h2>Qué es normativo y qué es implementación</h2>
 *
 * <ul>
 *   <li><strong>Normativo:</strong> access token de 15 minutos, stateless, que viaja en la cabecera
 *       {@code Authorization: Bearer}, <em>solo en memoria del frontend</em> (§7.1, P-01 CLOSED).</li>
 *   <li><strong>Decisión de implementación (AD-23):</strong> firma simétrica <strong>HS256</strong> con
 *       clave tomada del entorno. Ningún algoritmo concreto está aprobado por P-01/P-02; queda
 *       registrado como decisión técnica de Fase 2, no como decisión de producto.</li>
 * </ul>
 *
 * <p>El access token <strong>no</strong> lleva información que deba cambiar sin que el servidor lo
 * note. Lleva el identificador del usuario y su rol para que la capa de autorización pueda leerlos
 * sin tocar la base de datos, pero <strong>todas</strong> las peticiones siguen verificando contra
 * la base de datos que el usuario existe y está activo (§7.1 "verificación del usuario en cada
 * request", RF-IA-14). Por eso un access token de un usuario desactivado deja de valer de inmediato
 * y no se necesita ninguna lista negra de access tokens.
 *
 * <p>{@code patientId} <strong>no</strong> va en el token: se resuelve en
 * {@code GET /api/auth/me}, que es la fuente autoritativa (P-08 CLOSED, §14.3).
 */
@Component
public class JwtTokenService {

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_TOKEN_TYPE = "typ";

    private final SecretKey signingKey;
    private final String issuer;
    private final Duration accessTokenTtl;
    private final Clock clock;

    public JwtTokenService(SecurityProperties properties, Clock clock) {
        byte[] secretBytes = properties.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "dentalclinic.security.jwt.secret debe tener al menos 32 bytes para HS256");
        }
        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
        this.issuer = properties.jwt().issuer();
        this.accessTokenTtl = properties.jwt().accessTokenTtl();
        this.clock = clock;
    }

    /** Access token nuevo para una sesión autenticada. */
    public IssuedAccessToken issue(User user, Instant now) {
        Instant expiresAt = now.plus(accessTokenTtl);
        String token = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(issuer)
                .subject(user.getId().toString())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_TOKEN_TYPE, "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
        return new IssuedAccessToken(token, expiresAt);
    }

    /**
     * Verifica firma, emisor y expiración.
     *
     * @return los claims si el token es válido; vacío si está mal formado, alterado, expirado
     *     o no es un access token
     */
    public Optional<AccessTokenClaims> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(issuer)
                    // El mismo reloj con el que se emiten los tokens valida su expiración:
                    // en producción es UTC del sistema; en tests es el reloj congelable.
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!"access".equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
                return Optional.empty();
            }
            Long userId = Long.valueOf(claims.getSubject());
            Role role = Role.valueOf(claims.get(CLAIM_ROLE, String.class));
            return Optional.of(new AccessTokenClaims(userId, role));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public Duration accessTokenTtl() {
        return accessTokenTtl;
    }

    public record IssuedAccessToken(String value, Instant expiresAt) {
    }

    /** Contenido verificado de un access token. La autoridad sigue siendo la base de datos. */
    public record AccessTokenClaims(Long userId, Role role) {
    }
}
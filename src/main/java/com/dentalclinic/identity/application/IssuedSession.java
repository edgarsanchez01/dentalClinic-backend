package com.dentalclinic.identity.application;

import java.time.Instant;
import java.util.UUID;

import com.dentalclinic.identity.domain.User;

/**
 * Resultado de un flujo que emite una sesión.
 *
 * @param refreshToken el refresh token <strong>en claro</strong>: solo existe aquí, en el borde
 *     HTTP que lo escribe en la cookie {@code HttpOnly}, y nunca se persiste ni se registra. El
 *     token en claro no vuelve a existir después de esta llamada
 * @param familyId identificador de la familia, necesario para que el logout revoque solo esta
 *     sesión y no las demás del mismo usuario
 */
public record IssuedSession(
        User user,
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        UUID familyId) {
}
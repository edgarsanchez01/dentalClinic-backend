package com.dentalclinic.identity;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fuente de tiempo mutable compartida por los tests de integración de Fase 2.
 *
 * <p>La aplicación inyecta un único {@code Clock} (TimeConfiguration). En los tests se sustituye
 * por un reloj fijo que lee este {@code AtomicReference}, de modo que cada test puede avanzar el
 * tiempo de forma determinista para comprobar los bordes de expiración (access token, refresh,
 * invitación, recuperación) sin dormir.
 */
public final class TestTime {

    public static final Instant BASE = Instant.parse("2026-10-06T08:00:00Z");

    private static final AtomicReference<Instant> NOW = new AtomicReference<>(BASE);

    private TestTime() {
    }

    public static Instant now() {
        return NOW.get();
    }

    public static void reset() {
        NOW.set(BASE);
    }

    public static void advance(Duration duration) {
        NOW.updateAndGet(now -> now.plus(duration));
    }
}
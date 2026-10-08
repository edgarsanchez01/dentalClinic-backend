package com.dentalclinic.foundation;

/**
 * Se lanza cuando una barrera de las guardas de fixtures falla (Architecture.md 13.3,
 * Agents.md Regla 14). Provoca el fallo del arranque: el proceso se detiene en lugar
 * de sembrar una base de datos que no debe sembrarse.
 */
public class FixturesBlockedException extends RuntimeException {

    public FixturesBlockedException(String message) {
        super(message);
    }
}
package com.dentalclinic.identity.api;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Cuerpo de error único de toda la API.
 *
 * <p>El shape es <strong>normativo</strong> (PRD.md §"Contrato" y Architecture.md §8.1):
 * <pre>
 * {
 *   "status": 409,
 *   "error": "Conflict",
 *   "message": "Ese horario ya fue tomado. Selecciona otro horario.",
 *   "path": "/api/appointments",
 *   "timestamp": "2026-10-04T12:00:00",
 *   "fieldErrors": { "startAt": "No puede ser en el pasado" }
 * }
 * </pre>
 *
 * <p>{@code message} va siempre en español y orientado a la acción. Nunca incluye un stack trace
 * ni internals (Architecture.md §8.2, Design System.md: "Nunca se muestra un stack trace ni un
 * código de error interno al usuario final").
 *
 * @param fieldErrors solo presente en errores de validación de DTO (400)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        int status,
        String error,
        String message,
        String path,
        Instant timestamp,
        Map<String, String> fieldErrors) {

    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(status, error, message, path, Instant.now(), null);
    }

    public static ErrorResponse of(int status,
                                   String error,
                                   String message,
                                   String path,
                                   Map<String, String> fieldErrors) {
        return new ErrorResponse(status, error, message, path, Instant.now(), fieldErrors);
    }
}
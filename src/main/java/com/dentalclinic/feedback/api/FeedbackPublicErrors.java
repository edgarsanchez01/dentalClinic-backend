package com.dentalclinic.feedback.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** No incluye URI, cuerpo, token, identificadores ni mensajes de excepciones en respuesta o logs. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = FeedbackPublicController.class)
public class FeedbackPublicErrors {
    @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<Problem> invalid() {
        return ResponseEntity.badRequest().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new Problem("Selecciona de 1 a 5 estrellas y un comentario de hasta 2000 caracteres."));
    }
    public record Problem(String message) { }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Problem> unavailable() {
        return ResponseEntity.status(503).cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new Problem("No se pudo procesar la invitación. Inténtalo de nuevo."));
    }
}

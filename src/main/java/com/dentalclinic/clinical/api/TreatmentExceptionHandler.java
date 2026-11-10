package com.dentalclinic.clinical.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.dentalclinic.clinical.domain.InvalidTreatmentException;
import com.dentalclinic.identity.api.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Errores de entrada del registro de tratamientos: datos de dominio invalidos, JSON
 * malformado (p. ej. un estado no reconocido) o numeros con formato incorrecto se traducen a 400.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {TreatmentController.class, TreatmentEvolutionController.class})
public class TreatmentExceptionHandler {

    @ExceptionHandler({InvalidTreatmentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> invalid(Exception error, HttpServletRequest request) {
        String message = error instanceof InvalidTreatmentException ? error.getMessage()
                : "Revisa el formato de los datos del tratamiento.";
        return ResponseEntity.badRequest().body(ErrorResponse.of(400,
                HttpStatus.BAD_REQUEST.getReasonPhrase(), message, request.getRequestURI()));
    }
}
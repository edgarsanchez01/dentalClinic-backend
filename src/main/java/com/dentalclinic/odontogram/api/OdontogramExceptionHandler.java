package com.dentalclinic.odontogram.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.ConcurrencyFailureException;
import com.dentalclinic.scheduling.domain.ScheduleConflictException;

import com.dentalclinic.identity.api.ErrorResponse;
import com.dentalclinic.odontogram.domain.InvalidToothException;
import com.dentalclinic.odontogram.domain.ToothRecordAlreadyExistsException;
import com.dentalclinic.odontogram.domain.ToothSurfaceAlreadyExistsException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Errores de entrada del odontograma: datos de dominio inválidos (FDI, superficie o condición),
 * JSON malformado (p. ej. una condición no reconocida) o un FDI no numérico en la ruta se traducen
 * a 400; registrar dos veces la misma pieza o superficie es un conflicto de estado (409).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {OdontogramController.class, OdontogramHistoryController.class})
public class OdontogramExceptionHandler {

    @ExceptionHandler({InvalidToothException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> invalid(Exception error, HttpServletRequest request) {
        String message = error instanceof InvalidToothException ? error.getMessage()
                : "Revisa el formato de los datos del odontograma.";
        return ResponseEntity.badRequest().body(ErrorResponse.of(400,
                HttpStatus.BAD_REQUEST.getReasonPhrase(), message, request.getRequestURI()));
    }

    @ExceptionHandler({ToothRecordAlreadyExistsException.class,
            ToothSurfaceAlreadyExistsException.class, ScheduleConflictException.class,
            DataIntegrityViolationException.class, ConcurrencyFailureException.class})
    public ResponseEntity<ErrorResponse> conflict(Exception error, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(409,
                HttpStatus.CONFLICT.getReasonPhrase(),
                error instanceof DataIntegrityViolationException || error instanceof ConcurrencyFailureException
                        ? "El registro entra en conflicto con los datos existentes. Revisa el borrador antes de repetir."
                        : error.getMessage(), request.getRequestURI()));
    }
}

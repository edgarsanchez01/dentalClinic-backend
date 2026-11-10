package com.dentalclinic.financial.api;

import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.dentalclinic.financial.domain.FinancialContextUnavailableException;
import com.dentalclinic.identity.api.ErrorResponse;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;

/**
 * Contrato de errores <strong>exclusivo</strong> del contexto financiero de una atencion
 * (UX-05B.1, Etapa 2, solo lectura).
 *
 * <p>El endpoint comparte modulo con las operaciones de escritura, pero <strong>no</strong> su
 * contrato de errores: un fallo tecnico de esta lectura nunca debe convertirse en un {@code 409} con
 * instrucciones para reintentar con la misma {@code Idempotency-Key}. Por eso este advice sustituye
 * al financiero general para este controlador:
 *
 * <ul>
 *   <li>conjunto no verificable &rarr; {@code 409 FINANCIAL_CONTEXT_UNAVAILABLE} generico;</li>
 *   <li>cita inexistente o fuera de alcance &rarr; {@code 404} sin revelar datos;</li>
 *   <li>rol sin acceso &rarr; {@code 403};</li>
 *   <li>formato invalido &rarr; {@code 400};</li>
 *   <li>fallo tecnico inesperado &rarr; {@code 500} generico, sin mensaje de idempotencia y sin
 *       contexto vacio.</li>
 * </ul>
 *
 * <p>Todas las respuestas llevan {@code Cache-Control: no-store}: son datos financieros sensibles.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {AppointmentFinancialContextController.class, CollectionQueueController.class})
public class AppointmentFinancialContextExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AppointmentFinancialContextExceptionHandler.class);
    private static final String GENERIC_500 = "Ha ocurrido un error inesperado. Intentalo de nuevo.";

    @ExceptionHandler(FinancialContextUnavailableException.class)
    public ResponseEntity<ErrorResponse> contextUnavailable(FinancialContextUnavailableException error,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).header("Cache-Control", "no-store")
                .body(new ErrorResponse(HttpStatus.CONFLICT.value(), HttpStatus.CONFLICT.getReasonPhrase(),
                        error.getMessage(), request.getRequestURI(), Instant.now(), null,
                        "FINANCIAL_CONTEXT_UNAVAILABLE"));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            com.dentalclinic.scheduling.domain.exception.InvalidScheduleException.class,
            HandlerMethodValidationException.class, MissingServletRequestParameterException.class,
            ConstraintViolationException.class})
    public ResponseEntity<ErrorResponse> badRequest(Exception error, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST,
                "Revisa el formato y los campos de la solicitud financiera.", request);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(NotFoundException error, HttpServletRequest request) {
        String message = error.getMessage() == null
                ? "No encontramos el recurso solicitado." : error.getMessage();
        return response(HttpStatus.NOT_FOUND, message, request);
    }

    @ExceptionHandler(ForbiddenOperationException.class)
    public ResponseEntity<ErrorResponse> forbiddenOperation(ForbiddenOperationException error,
            HttpServletRequest request) {
        String message = error.getMessage() == null
                ? "No tienes permiso para realizar esta accion." : error.getMessage();
        return response(HttpStatus.FORBIDDEN, message, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> accessDenied(AccessDeniedException error,
            HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, "No tienes permiso para realizar esta accion.", request);
    }

    /**
     * Fallo tecnico inesperado. No se convierte en 409 ni en un contexto vacio: el cliente debe saber
     * que la consulta fallo, y el mensaje no expone SQL, locks ni trazas.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception error, HttpServletRequest request) {
        log.error("Fallo no controlado en el contexto financiero; tipo={}", error.getClass().getSimpleName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_500, request);
    }

    private static ResponseEntity<ErrorResponse> response(HttpStatus status, String message,
            HttpServletRequest request) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(ErrorResponse.of(status.value(), status.getReasonPhrase(), message,
                        request.getRequestURI()));
    }
}

package com.dentalclinic.financial.api;

import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.dentalclinic.financial.domain.DefinitiveFinancialConflictException;
import com.dentalclinic.financial.domain.DefinitiveInvalidChargeException;
import com.dentalclinic.financial.domain.FinancialConflictException;
import com.dentalclinic.financial.domain.FinancialIntentClosedException;
import com.dentalclinic.financial.domain.FinancialIntentClosureBlockedException;
import com.dentalclinic.financial.domain.FinancialIntentClosureUncertainException;
import com.dentalclinic.financial.domain.FinancialIntentCommandConflictException;
import com.dentalclinic.financial.domain.FinancialIntentCommandUnavailableException;
import com.dentalclinic.financial.domain.FinancialIntentReceiptIncompatibleException;
import com.dentalclinic.financial.domain.InvalidChargeException;
import com.dentalclinic.financial.domain.InvalidFinancialIntentRequestException;
import com.dentalclinic.financial.domain.InvalidFinancialMovementException;
import com.dentalclinic.identity.api.ErrorResponse;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;

/** Traduccion B4 acotada, sin cambiar el contrato HTTP de escritores antiguos. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = FinancialIntentController.class)
public class FinancialIntentExceptionHandler {
    @ExceptionHandler(InvalidFinancialIntentRequestException.class)
    public ResponseEntity<ErrorResponse> invalid(InvalidFinancialIntentRequestException error, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, error.code(), error.getMessage(), request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> format(Exception error, HttpServletRequest request) {
        return invalid(new InvalidFinancialIntentRequestException(false), request);
    }

    @ExceptionHandler(ForbiddenOperationException.class)
    public ResponseEntity<ErrorResponse> forbidden(Exception error, HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, null, "No tienes permiso para realizar esta accion.", request);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> missing(Exception error, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, null, "No encontramos la intencion o el recurso solicitado.", request);
    }

    @ExceptionHandler(FinancialIntentReceiptIncompatibleException.class)
    public ResponseEntity<ErrorResponse> incompatible(RuntimeException error, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "INTENT_RECEIPT_INCOMPATIBLE", error.getMessage(), request);
    }

    @ExceptionHandler(FinancialIntentClosureUncertainException.class)
    public ResponseEntity<ErrorResponse> closure(RuntimeException error, HttpServletRequest request) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "INTENT_CLOSURE_UNCERTAIN",
                "No se pudo acreditar el cierre. Verifica la misma identidad antes de reanudar.", request);
    }

    @ExceptionHandler({InvalidChargeException.class, InvalidFinancialMovementException.class, FinancialConflictException.class,
            FinancialIntentCommandUnavailableException.class})
    public ResponseEntity<ErrorResponse> business(RuntimeException error, HttpServletRequest request) {
        String code = switch (error) {
            case FinancialIntentCommandConflictException ignored -> "INTENT_COMMAND_CONFLICT";
            case FinancialIntentCommandUnavailableException ignored -> "INTENT_COMMAND_UNAVAILABLE";
            case FinancialIntentClosedException ignored -> "INTENT_CLOSED";
            case FinancialIntentClosureBlockedException ignored -> "INTENT_HAS_RECEIPT";
            case DefinitiveFinancialConflictException definitive -> definitive.reason().name();
            case DefinitiveInvalidChargeException definitive -> definitive.reason().name();
            case InvalidFinancialMovementException ignored -> "INTENT_REQUEST_INVALID";
            default -> "FINANCIAL_REQUEST_REJECTED";
        };
        var status = error instanceof FinancialConflictException || error instanceof FinancialIntentCommandUnavailableException
                ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return response(status, code, error.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> technical(Exception error, HttpServletRequest request) {
        boolean read = "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
        return response(HttpStatus.SERVICE_UNAVAILABLE, read ? "INTENT_VERIFICATION_UNAVAILABLE" : "INTENT_RESULT_UNCERTAIN",
                "No se pudo verificar el resultado. Consulta la misma identidad antes de reanudar.", request);
    }

    private static ResponseEntity<ErrorResponse> response(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(new ErrorResponse(status.value(),
                status.getReasonPhrase(), message, request.getRequestURI(), Instant.now(), null, code));
    }
}

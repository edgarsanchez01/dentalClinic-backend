package com.dentalclinic.financial.api;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.dentalclinic.financial.domain.FinancialConflictException;
import com.dentalclinic.financial.domain.InvalidChargeException;
import com.dentalclinic.financial.domain.InvalidFinancialMovementException;
import com.dentalclinic.identity.api.ErrorResponse;

/** Solo rutas financieras; conserva el contrato global y nunca expone SQL/locks/trazas. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {ChargeController.class, FinancialReportController.class})
public class FinancialExceptionHandler {
    @ExceptionHandler({InvalidChargeException.class, InvalidFinancialMovementException.class})
    public ResponseEntity<ErrorResponse> invalid(RuntimeException error, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, error.getMessage(), request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class})
    public ResponseEntity<ErrorResponse> format(Exception error, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "Revisa el formato y los campos de la solicitud financiera.", request);
    }

    @ExceptionHandler(FinancialConflictException.class)
    public ResponseEntity<ErrorResponse> conflict(FinancialConflictException error, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, error.getMessage(), request);
    }

    @ExceptionHandler({DataIntegrityViolationException.class, ConcurrencyFailureException.class,
            jakarta.persistence.LockTimeoutException.class, jakarta.persistence.PessimisticLockException.class})
    public ResponseEntity<ErrorResponse> persistence(RuntimeException error, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT,
                "La operacion entra en conflicto con los datos actuales. Reintenta con la misma clave si la solicitud no cambio.", request);
    }

    private static ResponseEntity<ErrorResponse> response(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(ErrorResponse.of(status.value(), status.getReasonPhrase(),
                message, request.getRequestURI()));
    }
}

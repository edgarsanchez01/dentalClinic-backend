package com.dentalclinic.checkout.api;

import java.time.Instant;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.dentalclinic.checkout.domain.CheckoutException;
import com.dentalclinic.financial.domain.FinancialConflictException;
import com.dentalclinic.financial.domain.FinancialContextUnavailableException;
import com.dentalclinic.financial.domain.CheckoutChargeAmbiguousException;
import com.dentalclinic.financial.domain.CheckoutEconomicReferenceRequiredException;
import com.dentalclinic.financial.domain.CheckoutPaymentExceedsBalanceException;
import com.dentalclinic.identity.api.ErrorResponse;
import com.dentalclinic.identity.domain.exception.ForbiddenOperationException;
import com.dentalclinic.identity.domain.exception.NotFoundException;

/** Errors are classified after the transactional service proxy has rolled back. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AppointmentCheckoutController.class)
public class AppointmentCheckoutExceptionHandler {
    @ExceptionHandler(CheckoutException.class)
    public ResponseEntity<ErrorResponse> business(CheckoutException error, HttpServletRequest request) {
        HttpStatus status = switch (error.code()) {
            case CHECKOUT_INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case CHECKOUT_RETRYABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.CONFLICT;
        };
        return response(status, error.code().name(), error.getMessage(), request);
    }

    @ExceptionHandler(FinancialConflictException.class)
    public ResponseEntity<ErrorResponse> financial(FinancialConflictException error,
            HttpServletRequest request) {
        String code = error instanceof CheckoutChargeAmbiguousException ? "CHECKOUT_CHARGE_AMBIGUOUS"
                : error instanceof CheckoutEconomicReferenceRequiredException
                        ? "CHECKOUT_ECONOMIC_REFERENCE_REQUIRED"
                : error instanceof CheckoutPaymentExceedsBalanceException
                        ? "CHECKOUT_PAYMENT_EXCEEDS_BALANCE" : "CHECKOUT_CONTEXT_CHANGED";
        return response(HttpStatus.CONFLICT, code,
                "No se puede confirmar el cierre con el contexto indicado. Revisa la atencion.", request);
    }

    @ExceptionHandler(FinancialContextUnavailableException.class)
    public ResponseEntity<ErrorResponse> unavailable(Exception error, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "FINANCIAL_CONTEXT_UNAVAILABLE",
                "No se pudo verificar por completo el contexto financiero de la atencion.", request);
    }

    @ExceptionHandler({ConcurrencyFailureException.class, org.springframework.dao.QueryTimeoutException.class,
            jakarta.persistence.LockTimeoutException.class, jakarta.persistence.PessimisticLockException.class})
    public ResponseEntity<ErrorResponse> retryable(Exception error, HttpServletRequest request) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "CHECKOUT_RETRYABLE",
                "Contencion temporal. Reintenta la misma solicitud con la misma clave.", request);
    }

    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.method.annotation.HandlerMethodValidationException.class,
            jakarta.validation.ConstraintViolationException.class})
    public ResponseEntity<ErrorResponse> invalid(Exception error, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "CHECKOUT_INVALID_REQUEST",
                "Revisa el formato de la solicitud de cierre.", request);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(Exception error, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, null, "No encontramos el recurso solicitado.", request);
    }

    @ExceptionHandler({ForbiddenOperationException.class, org.springframework.security.access.AccessDeniedException.class})
    public ResponseEntity<ErrorResponse> forbidden(Exception error, HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, null, "No tienes permiso para cerrar esta atencion.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception error, HttpServletRequest request) {
        org.slf4j.LoggerFactory.getLogger(getClass()).error("Fallo de checkout; tipo={}",
                error.getClass().getSimpleName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, null,
                "No se pudo confirmar la respuesta. Recupera con la misma solicitud y clave.", request);
    }

    private static ResponseEntity<ErrorResponse> response(HttpStatus status, String code,
            String message, HttpServletRequest request) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(new ErrorResponse(status.value(), status.getReasonPhrase(), message,
                        request.getRequestURI(), Instant.now(), null, code));
    }
}

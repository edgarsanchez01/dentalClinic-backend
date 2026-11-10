package com.dentalclinic.financial.api;

import java.time.Instant;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import com.dentalclinic.financial.domain.*;
import com.dentalclinic.identity.api.ErrorResponse;
import com.dentalclinic.identity.domain.exception.*;
import com.dentalclinic.scheduling.domain.ScheduleConflictException;
import com.dentalclinic.scheduling.domain.exception.InvalidScheduleException;

/** Only collection writes. Unexpected persistence failures are not disguised as business conflicts. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AppointmentCollectionController.class)
public class CollectionExceptionHandler {
    @ExceptionHandler({InvalidFinancialMovementException.class, InvalidScheduleException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> invalid(Exception error, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "COLLECTION_INVALID_REQUEST", "Revisa la solicitud de cobro.", request);
    }

    @ExceptionHandler({FinancialConflictException.class, ScheduleConflictException.class})
    public ResponseEntity<ErrorResponse> conflict(Exception error, HttpServletRequest request) {
        String code = error instanceof CheckoutChargeAmbiguousException ? "COLLECTION_CHARGE_AMBIGUOUS"
                : error instanceof CheckoutEconomicReferenceRequiredException ? "COLLECTION_ECONOMIC_REFERENCE_REQUIRED"
                : error instanceof CheckoutPaymentExceedsBalanceException ? "COLLECTION_PAYMENT_EXCEEDS_BALANCE"
                : "COLLECTION_CONFLICT";
        return response(HttpStatus.CONFLICT, code, "El cobro no coincide con el contexto actual. Vuelve a consultarlo.", request);
    }

    @ExceptionHandler(FinancialContextUnavailableException.class)
    public ResponseEntity<ErrorResponse> context(Exception error, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "FINANCIAL_CONTEXT_UNAVAILABLE", "No se pudo verificar el contexto financiero completo.", request);
    }

    @ExceptionHandler({ForbiddenOperationException.class, org.springframework.security.access.AccessDeniedException.class})
    public ResponseEntity<ErrorResponse> forbidden(Exception error, HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, null, "No tienes permiso para gestionar este cobro.", request);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> missing(Exception error, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, null, "No encontramos el recurso solicitado.", request);
    }

    @ExceptionHandler({org.springframework.dao.ConcurrencyFailureException.class,
            org.springframework.dao.QueryTimeoutException.class, jakarta.persistence.LockTimeoutException.class,
            jakarta.persistence.PessimisticLockException.class})
    public ResponseEntity<ErrorResponse> retryable(Exception error, HttpServletRequest request) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "COLLECTION_RETRYABLE",
                "Contencion temporal. Reintenta con la misma solicitud y clave.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception error, HttpServletRequest request) {
        org.slf4j.LoggerFactory.getLogger(getClass()).error("Fallo de cobro; tipo={}", error.getClass().getSimpleName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, null,
                "No se pudo confirmar la respuesta. Recupera con la misma solicitud y clave.", request);
    }

    private static ResponseEntity<ErrorResponse> response(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(new ErrorResponse(
                status.value(), status.getReasonPhrase(), message, request.getRequestURI(), Instant.now(), null, code));
    }
}

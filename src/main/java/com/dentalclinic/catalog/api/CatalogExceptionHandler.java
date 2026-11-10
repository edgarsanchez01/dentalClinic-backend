package com.dentalclinic.catalog.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.servlet.http.HttpServletRequest;

import com.dentalclinic.catalog.domain.InvalidCatalogItemException;
import com.dentalclinic.identity.api.ErrorResponse;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {ServiceCatalogController.class, PublicServiceCatalogController.class})
public class CatalogExceptionHandler {
    @ExceptionHandler({InvalidCatalogItemException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> invalid(Exception error, HttpServletRequest request) {
        String message = error instanceof InvalidCatalogItemException ? error.getMessage()
                : "Revisa el formato de los datos del servicio.";
        return ResponseEntity.badRequest().body(ErrorResponse.of(400, HttpStatus.BAD_REQUEST.getReasonPhrase(),
                message, request.getRequestURI()));
    }
}

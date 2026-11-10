package com.dentalclinic.clinical.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.dentalclinic.identity.api.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;

/** Input errors for notes, without changing JSON coercion or authorization globally. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ClinicalController.class)
public class ClinicalInputExceptionHandler {
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> invalid(HttpMessageNotReadableException error, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(400, HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Revisa el contenido y el contexto de la entrada clínica. La cita y la ocurrencia deben ser números enteros válidos.", request.getRequestURI()));
    }
}

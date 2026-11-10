package com.dentalclinic.medicalintake.api;

import java.time.Instant;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import com.dentalclinic.identity.api.ErrorResponse;
import com.dentalclinic.medicalintake.domain.IntakeException;

@RestControllerAdvice(assignableTypes = {MedicalIntakeController.class, ProfessionalIntakeController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IntakeExceptionHandler {
    @ExceptionHandler(IntakeException.class)
    public ResponseEntity<ErrorResponse> intake(IntakeException e, jakarta.servlet.http.HttpServletRequest request) {
        HttpStatus status = "INTAKE_VALIDATION_FAILED".equals(e.code()) ? HttpStatus.BAD_REQUEST : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(new ErrorResponse(
                status.value(), status.getReasonPhrase(), e.getMessage(), request.getRequestURI(), Instant.now(),
                e.fieldErrors().isEmpty() ? null : e.fieldErrors(), e.code()));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException ignored,
            jakarta.servlet.http.HttpServletRequest request) {
        return ResponseEntity.badRequest().header("Cache-Control", "no-store").body(new ErrorResponse(400,
                "Bad Request", "Revisa el formato de los datos y las fechas del cuestionario.", request.getRequestURI(),
                Instant.now(), null, "INTAKE_INVALID_FORMAT"));
    }
}
